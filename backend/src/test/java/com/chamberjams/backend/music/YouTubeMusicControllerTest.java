package com.chamberjams.backend.music;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YouTubeMusicControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void servesRequestedAudioByteRange() throws Exception {
        YouTubeMusicController controller = controllerFor(audioFile("track.m4a"));

        ResponseEntity<?> response = controller.streamAudio("abcdefghijk", "bytes=1-3");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 1-3/6", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(3L, response.getHeaders().getContentLength());
        Resource body = assertInstanceOf(Resource.class, response.getBody());
        try (var inputStream = body.getInputStream()) {
            assertArrayEquals(new byte[] {2, 3, 4}, inputStream.readAllBytes());
        }
    }

    @Test
    void rejectsAudioByteRangeOutsideTheFile() throws Exception {
        YouTubeMusicController controller = controllerFor(audioFile("track.m4a"));

        ResponseEntity<?> response = controller.streamAudio("abcdefghijk", "bytes=8-10");

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */6", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void writesPartialAudioBytesForMediaClients() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(controllerFor(audioFile("track.m4a")))
                .build();

        mockMvc.perform(get("/api/music/stream/abcdefghijk")
                        .header(HttpHeaders.RANGE, "bytes=1-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1-3/6"))
                .andExpect(content().contentType("audio/mp4"))
                .andExpect(content().bytes(new byte[] {2, 3, 4}));
    }

    @Test
    void servesFullAudioWhenNoRangeIsRequested() throws Exception {
        YouTubeMusicController controller = controllerFor(audioFile("track.mp3"));

        ResponseEntity<?> response = controller.streamAudio("abcdefghijk", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(6L, response.getHeaders().getContentLength());
        assertEquals("audio/mpeg", response.getHeaders().getContentType().toString());
        assertInstanceOf(Resource.class, response.getBody());
    }

    private Path audioFile(String name) throws Exception {
        Path audioFile = tempDir.resolve(name);
        Files.write(audioFile, new byte[] {1, 2, 3, 4, 5, 6});
        return audioFile;
    }

    private YouTubeMusicController controllerFor(Path audioFile) throws Exception {
        YouTubeMusicService musicService = new YouTubeMusicService() {
            @Override
            public java.io.File getOrDownloadAudio(String videoId) {
                return audioFile.toFile();
            }
        };
        return new YouTubeMusicController(musicService);
    }
}
