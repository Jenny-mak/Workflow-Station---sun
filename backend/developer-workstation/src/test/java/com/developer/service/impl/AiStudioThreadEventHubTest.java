package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioThreadEvent;
import com.developer.service.AiStudioProposalJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 按功能单元分发、按订阅者换算 mine、不下发触发者 id、断开的连接被清理、新订阅者补发进行中的作业。 */
class AiStudioThreadEventHubTest {

    /** 记录 send 内容的 emitter */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        boolean broken;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) throw new IOException("client gone");
            StringBuilder sb = new StringBuilder();
            builder.build().forEach(part -> sb.append(part.getData()));
            frames.add(sb.toString());
        }

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            if (broken) throw new IOException("client gone");
        }
    }

    private final AiStudioProposalJobService jobs = mock(AiStudioProposalJobService.class);
    private final List<RecordingEmitter> created = new ArrayList<>();

    private AiStudioThreadEventHub hub() {
        return new AiStudioThreadEventHub(new ObjectMapper(), jobs, 300, false) {
            @Override
            SseEmitter newEmitter(long timeout) {
                RecordingEmitter e = new RecordingEmitter();
                created.add(e);
                return e;
            }
        };
    }

    @Test
    void eventsReachOnlyTheSameFunctionUnitWithPerSubscriberMine() {
        AiStudioThreadEventHub hub = hub();
        hub.subscribe(1L, "u-alice");
        hub.subscribe(1L, "u-bob");
        hub.subscribe(2L, "u-carol");

        hub.onThreadEvent(AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_ADDED, 1L, "TABLE_DESIGN", 42L, "u-alice"));

        String alice = created.get(0).frames.get(1);
        String bob = created.get(1).frames.get(1);
        assertTrue(alice.contains("event:MESSAGE_ADDED") && alice.contains("\"messageId\":42")
                && alice.contains("\"phase\":\"TABLE_DESIGN\"") && alice.contains("\"mine\":true"), alice);
        assertTrue(bob.contains("\"mine\":false"), bob);
        assertFalse(bob.contains("u-alice"), "the actor id is never sent");
        assertEquals(1, created.get(2).frames.size(), "other function units only got READY");
    }

    @Test
    void brokenConnectionsAreDroppedOnSend() {
        AiStudioThreadEventHub hub = hub();
        hub.subscribe(1L, "u-alice");
        hub.subscribe(1L, "u-bob");
        created.get(0).broken = true;

        hub.onThreadEvent(AiStudioThreadEvent.progress(1L, "u-bob"));
        assertEquals(1, hub.subscriberCount(1L));
        hub.onThreadEvent(AiStudioThreadEvent.progress(1L, "u-bob"));
        assertEquals(3, created.get(1).frames.size());
        assertTrue(created.get(1).frames.get(2).contains("event:PROGRESS_UPDATED"));
    }

    @Test
    void newSubscribersLearnWhoIsGeneratingRightNow() {
        AiStudioProposalJobResponse running = AiStudioProposalJobResponse.builder()
                .jobId("j1").phase("FORM_DESIGN").authorName("Alice").build();
        when(jobs.activeJobs(1L)).thenReturn(List.of(new AiStudioProposalJobService.ActiveJob(running, "u-alice")));

        AiStudioThreadEventHub hub = hub();
        hub.subscribe(1L, "u-bob");
        List<String> frames = created.get(0).frames;
        assertTrue(frames.get(0).contains("event:READY"), frames.get(0));
        String started = frames.get(1);
        for (String part : List.of("event:PROPOSAL_STARTED", "\"jobId\":\"j1\"", "\"authorName\":\"Alice\"",
                "\"phase\":\"FORM_DESIGN\"", "\"mine\":false")) {
            assertTrue(started.contains(part), started);
        }
    }
}
