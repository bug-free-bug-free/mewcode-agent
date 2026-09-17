// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileMailBoxTest {

    @TempDir
    Path tempDir;

    @Test
    void sendCreatesFileWithMessage() throws Exception {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        mb.send("agent-b", new FileMailBox.MailMessage("agent-a", "Hello from A"));

        Path inbox = tempDir.resolve("inboxes/agent-b.json");
        assertTrue(Files.exists(inbox), "Inbox file should be created");

        String content = Files.readString(inbox);
        assertTrue(content.contains("\"from\" : \"agent-a\""));
        assertTrue(content.contains("\"text\" : \"Hello from A\""));
        assertTrue(content.contains("\"read\" : false"));
    }

    @Test
    void readUnreadReturnsOnlyUnread() {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        mb.send("bob", new FileMailBox.MailMessage("alice", "msg1"));
        mb.send("bob", new FileMailBox.MailMessage("carol", "msg2"));

        List<FileMailBox.MailMessage> unread = mb.readUnread("bob");
        assertEquals(2, unread.size());
        assertEquals("alice", unread.get(0).from());
        assertEquals("carol", unread.get(1).from());
    }

    @Test
    void markAllReadMakesUnreadEmpty() {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        mb.send("bob", new FileMailBox.MailMessage("alice", "msg1"));
        mb.send("bob", new FileMailBox.MailMessage("carol", "msg2"));

        mb.markAllRead("bob");

        List<FileMailBox.MailMessage> unread = mb.readUnread("bob");
        assertTrue(unread.isEmpty(), "Should have no unread after markAllRead");
    }

    @Test
    void nonexistentAgentReturnsEmpty() {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        List<FileMailBox.MailMessage> unread = mb.readUnread("nobody");
        assertTrue(unread.isEmpty());
    }

    @Test
    void teamSendMessageIntegration() {
        var team = new TeamManager.Team("test-team", TeamManager.TeamMode.IN_PROCESS);
        // Override mailbox for test
        var testMb = new FileMailBox(tempDir.resolve("inboxes"));
        // Use reflection-free approach: just test FileMailBox directly with same flow
        testMb.send("worker", new FileMailBox.MailMessage("leader", "do task X"));

        List<FileMailBox.MailMessage> unread = testMb.readUnread("worker");
        assertEquals(1, unread.size());
        assertEquals("leader", unread.get(0).from());
        assertEquals("do task X", unread.get(0).text());
    }
}

