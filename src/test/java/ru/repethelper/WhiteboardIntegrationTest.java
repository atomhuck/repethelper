package ru.repethelper;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;
import ru.repethelper.service.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WhiteboardIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage-path", () -> "target/test-whiteboard-uploads");
        registry.add("app.teacher.username", () -> "board_teacher");
        registry.add("app.teacher.password", () -> "secure-password");
        registry.add("app.teacher.name", () -> "Преподаватель доски");
        registry.add("app.teacher.code", () -> "board_teacher_code");
        registry.add("app.account-gate-enabled", () -> "false");
    }

    @Autowired WebApplicationContext context;
    @Autowired AccountService accounts;
    @Autowired ConnectionService connections;
    @Autowired LessonService lessons;
    @Autowired WhiteboardService whiteboards;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConnectionRequestRepository requestRepository;
    @Autowired WhiteboardRepository whiteboardRepository;
    @Autowired WhiteboardObjectRepository whiteboardObjectRepository;
    @Autowired WhiteboardNavigationService navigation;
    @LocalServerPort int port;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void persistsObjectsProtectsOwnershipAndCleansUpWithLesson() throws Exception {
        User teacher = accounts.requireByUsername("board_teacher");
        User student = acceptedStudent(teacher, "board_student");
        Lesson lesson = lessons.create(teacher, student.getId(),
                LocalDateTime.of(2026, 9, 2, 18, 0), 60);

        Whiteboard board = whiteboards.getOrCreate(teacher, lesson);
        assertThat(whiteboards.getOrCreate(student, lesson).getId()).isEqualTo(board.getId());

        ObjectNode path = validPath();
        UUID pathId = UUID.randomUUID();
        var created = whiteboards.createPath(student, board.getPublicId(), pathId, path);
        assertThat(created.changed()).isTrue();
        assertThat(created.revision()).isEqualTo(1);
        assertThat(whiteboards.createPath(student, board.getPublicId(), pathId, path).changed()).isFalse();
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects()).hasSize(1);

        MockMultipartFile image = new MockMultipartFile(
                "file", "scheme.png", "image/png", png(80, 50));
        var uploaded = whiteboards.uploadImage(student, board.getPublicId(), image, 100, 120);
        assertThat(uploaded.revision()).isEqualTo(2);
        assertThat(whiteboards.loadImage(student, board.getPublicId(), uploaded.object().id()).resource().exists()).isTrue();

        ObjectNode transform = (ObjectNode) uploaded.object().data().deepCopy();
        transform.put("left", 220);
        var moved = whiteboards.updateObject(student, board.getPublicId(), uploaded.object().id(),
                uploaded.object().version(), transform);
        assertThat(moved.object().version()).isEqualTo(uploaded.object().version() + 1);
        assertThatThrownBy(() -> whiteboards.updateObject(student, board.getPublicId(), uploaded.object().id(),
                uploaded.object().version(), transform))
                .isInstanceOf(WhiteboardService.VersionConflictException.class);

        User outsider = accounts.registerStudent("Чужой ученик", "board_outsider", "password123");
        mvc.perform(get("/boards/{id}", board.getPublicId())
                        .with(user(outsider.getUsername()).roles("STUDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/boards/{id}/snapshot", board.getPublicId())
                        .with(user(outsider.getUsername()).roles("STUDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/boards/{boardId}/images/{objectId}", board.getPublicId(), uploaded.object().id())
                        .with(user(outsider.getUsername()).roles("STUDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/lessons/{id}", lesson.getId())
                        .with(user(student.getUsername()).roles("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Доска занятия")));

        var deletedPath = whiteboards.deleteObject(student, board.getPublicId(), pathId);
        assertThat(deletedPath.changed()).isTrue();
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects())
                .extracting(WhiteboardService.ObjectView::id).doesNotContain(pathId);

        var deletedImage = whiteboards.deleteObject(student, board.getPublicId(), uploaded.object().id());
        assertThat(deletedImage.changed()).isTrue();
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects()).isEmpty();
        assertThatThrownBy(() -> whiteboards.loadImage(student, board.getPublicId(), uploaded.object().id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        whiteboards.createPath(teacher, board.getPublicId(), UUID.randomUUID(), path);
        whiteboards.uploadImage(teacher, board.getPublicId(),
                new MockMultipartFile("file", "clear.png", "image/png", png(40, 30)), 20, 30);
        assertThatThrownBy(() -> whiteboards.clear(student, board.getPublicId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        whiteboards.clear(teacher, board.getPublicId());
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects()).isEmpty();

        whiteboards.createPath(teacher, board.getPublicId(), UUID.randomUUID(), path);
        lessons.delete(teacher, lesson.getId());
        assertThat(whiteboardRepository.findById(board.getId())).isEmpty();
        assertThat(whiteboardObjectRepository.findAll())
                .noneMatch(item -> item.getBoard().getId().equals(board.getId()));
    }

    @Test
    void rejectsInvalidStrokeAndImage() {
        User teacher = accounts.requireByUsername("board_teacher");
        User student = acceptedStudent(teacher, "board_limits");
        Lesson lesson = lessons.create(teacher, student.getId(),
                LocalDateTime.of(2026, 9, 3, 18, 0), 60);
        Whiteboard board = whiteboards.getOrCreate(teacher, lesson);

        ObjectNode invalid = objectMapper.createObjectNode();
        invalid.put("stroke", "red");
        invalid.put("strokeWidth", 100);
        invalid.putArray("path").addArray().add("M").add(0).add(0);
        assertThatThrownBy(() -> whiteboards.createPath(student, board.getPublicId(), UUID.randomUUID(), invalid))
                .isInstanceOf(IllegalArgumentException.class);

        MockMultipartFile gif = new MockMultipartFile(
                "file", "animated.gif", "image/gif", "GIF89a".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> whiteboards.uploadImage(student, board.getPublicId(), gif, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "fake.png", "image/png", "GIF89a".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> whiteboards.uploadImage(student, board.getPublicId(), spoofed, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        lessons.delete(teacher, lesson.getId());
    }

    @Test
    void supportsTextBatchMovementUndoableDeletionRenameAndRelatedHistory() throws Exception {
        User teacher = accounts.requireByUsername("board_teacher");
        User student = acceptedStudent(teacher, "board_v2");
        Lesson lesson = lessons.create(teacher, student.getId(),
                LocalDateTime.of(2025, 9, 3, 18, 0), 60);
        Whiteboard board = whiteboards.getOrCreate(teacher, lesson);

        UUID pathId = UUID.randomUUID();
        var path = whiteboards.createPath(teacher, board.getPublicId(), pathId, validPath());
        UUID textId = UUID.randomUUID();
        ObjectNode styledText = validText();
        styledText.putObject("styles").putObject("0").putObject("0").put("fontSize", 44);
        var text = whiteboards.createText(student, board.getPublicId(), textId, styledText);
        assertThat(text.object().type()).isEqualTo(WhiteboardObjectType.TEXT);
        assertThat(text.object().data().path("styles").path("0").path("0").path("fontSize").asInt()).isEqualTo(44);

        ObjectNode unsafeText = validText();
        unsafeText.putObject("styles").putObject("0").putObject("0").put("fontFamily", "external-font");
        assertThatThrownBy(() -> whiteboards.createText(student, board.getPublicId(), UUID.randomUUID(), unsafeText))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("только размер");

        var moved = whiteboards.moveObjects(teacher, board.getPublicId(), java.util.List.of(
                new WhiteboardService.VersionedObject(pathId, path.object().version()),
                new WhiteboardService.VersionedObject(textId, text.object().version())), 25, -15);
        assertThat(moved.objects()).hasSize(2);
        assertThat(moved.objects()).allMatch(item -> item.version() == 1);

        MockMultipartFile image = new MockMultipartFile(
                "file", "undo.png", "image/png", png(60, 40));
        var uploaded = whiteboards.uploadImage(student, board.getPublicId(), image, 50, 60);
        UUID deleteOperation = UUID.randomUUID();
        var deleted = whiteboards.deleteObjects(student, board.getPublicId(), deleteOperation,
                java.util.List.of(pathId, textId, uploaded.object().id()));
        assertThat(deleted.objects()).hasSize(3);
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects()).isEmpty();
        assertThatThrownBy(() -> whiteboards.loadImage(student, board.getPublicId(), uploaded.object().id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        var restored = whiteboards.restoreObjects(student, board.getPublicId(), deleteOperation,
                deleted.objects().stream().map(item ->
                        new WhiteboardService.VersionedObject(item.id(), item.version())).toList());
        assertThat(restored.objects()).hasSize(3);
        assertThat(whiteboards.snapshot(student, board.getPublicId()).objects()).hasSize(3);
        assertThat(whiteboards.loadImage(student, board.getPublicId(), uploaded.object().id()).resource().exists()).isTrue();

        long revisionBeforeRename = whiteboards.snapshot(teacher, board.getPublicId()).revision();
        var renamed = whiteboards.rename(teacher, board.getPublicId(), "Разбор динамики");
        assertThat(renamed.displayName()).isEqualTo("Разбор динамики");
        assertThat(whiteboards.snapshot(student, board.getPublicId()).displayName()).isEqualTo("Разбор динамики");
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).revision()).isEqualTo(revisionBeforeRename);
        assertThatThrownBy(() -> whiteboards.rename(student, board.getPublicId(), "Чужое название"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        Lesson currentLesson = lessons.create(teacher, student.getId(),
                LocalDateTime.of(2026, 9, 3, 18, 0), 60);
        Whiteboard current = whiteboards.getOrCreate(student, currentLesson);
        assertThatThrownBy(() -> whiteboards.createPath(teacher, current.getPublicId(), pathId, validPath()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(whiteboards.rename(teacher, board.getPublicId(), "  ").displayName())
                .startsWith("Доска · ");
        var related = navigation.related(student, current.getPublicId(), null, 20,
                Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(related.items()).extracting(WhiteboardNavigationService.RelatedBoard::boardId)
                .contains(board.getPublicId());
        lessons.delete(teacher, currentLesson.getId());
        lessons.delete(teacher, lesson.getId());
    }

    @Test
    void twoAuthenticatedWebSocketParticipantsReceiveSavedStroke() throws Exception {
        User teacher = accounts.requireByUsername("board_teacher");
        User student = acceptedStudent(teacher, "board_realtime");
        Lesson lesson = lessons.create(teacher, student.getId(),
                LocalDateTime.of(2026, 9, 4, 18, 0), 60);
        Whiteboard board = whiteboards.getOrCreate(teacher, lesson);

        HttpClient teacherClient = login("board_teacher", "secure-password");
        HttpClient studentClient = login(student.getUsername(), "password123");
        MessageListener teacherMessages = new MessageListener();
        MessageListener studentMessages = new MessageListener();
        URI socketUri = URI.create("ws://localhost:" + port + "/ws/boards/" + board.getPublicId());

        WebSocket teacherSocket = teacherClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(socketUri, teacherMessages).get(5, TimeUnit.SECONDS);
        WebSocket studentSocket = studentClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(socketUri, studentMessages).get(5, TimeUnit.SECONDS);
        assertThat(teacherMessages.awaitContaining("presence.join", 5)).isNotNull();
        assertThat(studentMessages.awaitContaining("presence.join", 5)).isNotNull();

        UUID objectId = UUID.randomUUID();
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "stroke.commit");
        event.put("operationId", objectId.toString());
        event.put("objectId", objectId.toString());
        event.set("data", validPath());
        studentSocket.sendText(objectMapper.writeValueAsString(event), true).get(5, TimeUnit.SECONDS);

        String received = teacherMessages.awaitContaining(objectId.toString(), 5);
        assertThat(received).contains("object.created");
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects())
                .extracting(WhiteboardService.ObjectView::id).contains(objectId);

        UUID textId = UUID.randomUUID();
        UUID textOperationId = UUID.randomUUID();
        ObjectNode textEvent = objectMapper.createObjectNode();
        textEvent.put("type", "text.commit");
        textEvent.put("operationId", textOperationId.toString());
        textEvent.put("objectId", textId.toString());
        textEvent.set("data", validText());
        teacherSocket.sendText(objectMapper.writeValueAsString(textEvent), true).get(5, TimeUnit.SECONDS);
        assertThat(studentMessages.awaitContaining(textOperationId.toString(), 5)).contains("object.created");

        UUID moveOperationId = UUID.randomUUID();
        ObjectNode moveEvent = objectMapper.createObjectNode();
        moveEvent.put("type", "objects.move");
        moveEvent.put("operationId", moveOperationId.toString());
        moveEvent.put("deltaX", 12);
        moveEvent.put("deltaY", -8);
        ArrayNode movedObjects = moveEvent.putArray("objects");
        movedObjects.addObject().put("id", objectId.toString()).put("expectedVersion", 0);
        movedObjects.addObject().put("id", textId.toString()).put("expectedVersion", 0);
        studentSocket.sendText(objectMapper.writeValueAsString(moveEvent), true).get(5, TimeUnit.SECONDS);
        assertThat(teacherMessages.awaitContaining(moveOperationId.toString(), 5)).contains("objects.updated");

        UUID deleteOperationId = UUID.randomUUID();
        ObjectNode deleteEvent = objectMapper.createObjectNode();
        deleteEvent.put("type", "objects.delete");
        deleteEvent.put("operationId", deleteOperationId.toString());
        deleteEvent.putArray("objectIds").add(objectId.toString()).add(textId.toString());
        studentSocket.sendText(objectMapper.writeValueAsString(deleteEvent), true).get(5, TimeUnit.SECONDS);
        assertThat(teacherMessages.awaitContaining(deleteOperationId.toString(), 5)).contains("objects.deleted");
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects()).isEmpty();

        UUID restoreOperationId = UUID.randomUUID();
        ObjectNode restoreEvent = objectMapper.createObjectNode();
        restoreEvent.put("type", "objects.restore");
        restoreEvent.put("operationId", restoreOperationId.toString());
        restoreEvent.put("deleteOperationId", deleteOperationId.toString());
        ArrayNode restoredObjects = restoreEvent.putArray("objects");
        restoredObjects.addObject().put("id", objectId.toString()).put("expectedVersion", 2);
        restoredObjects.addObject().put("id", textId.toString()).put("expectedVersion", 2);
        studentSocket.sendText(objectMapper.writeValueAsString(restoreEvent), true).get(5, TimeUnit.SECONDS);
        assertThat(teacherMessages.awaitContaining(restoreOperationId.toString(), 5)).contains("objects.restored");
        assertThat(whiteboards.snapshot(teacher, board.getPublicId()).objects()).hasSize(2);

        studentSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        teacherSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        lessons.delete(teacher, lesson.getId());
    }

    private User acceptedStudent(User teacher, String username) {
        User student = accounts.registerStudent("Ученик " + username, username, "password123");
        connections.send(student, "board_teacher_code");
        ConnectionRequest request = requestRepository.findByStudentOrderByCreatedAtDesc(student).getFirst();
        connections.process(teacher, request.getId(), true);
        return student;
    }

    private ObjectNode validPath() {
        ObjectNode path = objectMapper.createObjectNode();
        path.put("stroke", "#64F5A6");
        path.put("strokeWidth", 4);
        path.put("left", 10);
        path.put("top", 12);
        ArrayNode commands = path.putArray("path");
        commands.addArray().add("M").add(0).add(0);
        commands.addArray().add("L").add(50).add(25);
        return path;
    }

    private ObjectNode validText() {
        ObjectNode text = objectMapper.createObjectNode();
        text.put("text", "Пример текста");
        text.put("left", 30);
        text.put("top", 40);
        text.put("fontSize", 28);
        text.put("fill", "#4F46E5");
        return text;
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.CYAN);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private HttpClient login(String username, String password) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String base = "http://localhost:" + port;
        HttpResponse<String> loginPage = client.send(
                HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Matcher matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage.body());
        assertThat(matcher.find()).isTrue();
        String form = "email=" + encode(username)
                + "&password=" + encode(password)
                + "&_csrf=" + encode(matcher.group(1));
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse("")).doesNotContain("error");
        return client;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class MessageListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String awaitContaining(String expected, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                String message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && message.contains(expected)) return message;
            }
            return null;
        }
    }
}
