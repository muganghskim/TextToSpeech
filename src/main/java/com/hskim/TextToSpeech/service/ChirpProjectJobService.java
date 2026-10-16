package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.Project3JobStatus;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.io.FileNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class ChirpProjectJobService {

    private static final Logger log = LoggerFactory.getLogger(ChirpProjectJobService.class);
    private static final Duration STATUS_RETENTION = Duration.ofHours(24);

    private final ChirpProjectTtsService synthesis;
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    // A single worker avoids concurrent Google requests from multiple long projects.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "chirp-project3-worker");
                thread.setDaemon(true);
                return thread;
            });

    public ChirpProjectJobService(ChirpProjectTtsService synthesis) {
        this.synthesis = synthesis;
    }

    public Project3JobStatus submit(ObjectNode input) {
        ChirpProjectTtsService.Workload workload = synthesis.workload(input);
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, workload);
        pruneFinished();
        jobs.put(id, job);
        try {
            executor.execute(() -> run(job, input.deepCopy()));
        } catch (RejectedExecutionException failure) {
            jobs.remove(id);
            log.warn("project3 queue full; rejected job={}", id);
            throw failure;
        }
        log.info("project3 accepted job={} scenes={} segments={}", id,
                workload.totalScenes(), workload.totalSegments());
        return job.snapshot();
    }

    public Project3JobStatus status(String id) throws FileNotFoundException {
        pruneFinished();
        Job job = jobs.get(id);
        if (job == null) {
            throw new FileNotFoundException("Project3 job was not found or its status expired.");
        }
        return job.snapshot();
    }

    private void run(Job job, ObjectNode input) {
        job.started();
        log.info("project3 started job={}", job.id);
        try {
            VideoProjectTtsResult result = synthesis.synthesize(input, progress -> {
                job.progress(progress);
                log.info("project3 segment {} job={} scene={}/{} sceneId={} segment={}/{} completed={}/{}",
                        progress.phase(), job.id, progress.sceneNumber(), progress.totalScenes(), progress.sceneId(),
                        progress.segmentNumber(), progress.segmentsInScene(),
                        progress.completedSegments(), progress.totalSegments());
            });
            job.completed(result);
            log.info("project3 completed job={} outputId={} durationSec={}",
                    job.id, result.id(), result.actualTotalDurationSec());
        } catch (Exception failure) {
            job.failed();
            log.error("project3 failed job={} completedSegments={}/{}",
                    job.id, job.snapshot().completedSegments(), job.totalSegments, failure);
        }
    }

    private void pruneFinished() {
        Instant cutoff = Instant.now().minus(STATUS_RETENTION);
        jobs.entrySet().removeIf(entry -> entry.getValue().finishedBefore(cutoff));
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }

    private static final class Job {
        private final String id;
        private final int totalScenes;
        private final int totalSegments;
        private final Instant createdAt = Instant.now();
        private String state = "QUEUED";
        private int completedSegments;
        private String currentSceneId;
        private int currentSceneNumber;
        private int currentSegmentNumber;
        private Instant startedAt;
        private Instant updatedAt = createdAt;
        private Instant finishedAt;
        private VideoProjectTtsResult result;
        private String error;

        private Job(String id, ChirpProjectTtsService.Workload workload) {
            this.id = id;
            this.totalScenes = workload.totalScenes();
            this.totalSegments = workload.totalSegments();
        }

        private synchronized void started() {
            state = "RUNNING";
            startedAt = Instant.now();
            updatedAt = startedAt;
        }

        private synchronized void progress(ChirpProjectTtsService.Progress progress) {
            completedSegments = progress.completedSegments();
            currentSceneId = progress.sceneId();
            currentSceneNumber = progress.sceneNumber();
            currentSegmentNumber = progress.segmentNumber();
            updatedAt = Instant.now();
        }

        private synchronized void completed(VideoProjectTtsResult value) {
            result = value;
            state = "COMPLETED";
            finishedAt = Instant.now();
            updatedAt = finishedAt;
        }

        private synchronized void failed() {
            state = "FAILED";
            error = "Speech synthesis or output file creation failed. Check the server log with this jobId.";
            finishedAt = Instant.now();
            updatedAt = finishedAt;
        }

        private synchronized boolean finishedBefore(Instant cutoff) {
            return finishedAt != null && finishedAt.isBefore(cutoff);
        }

        private synchronized Project3JobStatus snapshot() {
            int percent = state.equals("COMPLETED") ? 100
                    : Math.min(99, completedSegments * 100 / totalSegments);
            return new Project3JobStatus(id, state, "/text/synthesize-project3/jobs/" + id,
                    totalScenes, totalSegments, completedSegments, percent,
                    currentSceneId, currentSceneNumber, currentSegmentNumber,
                    createdAt, startedAt, updatedAt, finishedAt, result, error);
        }
    }
}
