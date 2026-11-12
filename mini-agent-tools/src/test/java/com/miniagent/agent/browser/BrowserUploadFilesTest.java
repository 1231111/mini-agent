package com.miniagent.agent.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BrowserUploadFilesTest {
    @TempDir Path root;
    @Test void acceptsWorkspaceAndOwnedUploadButRejectsEscapeAndAnotherUser() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path owned = Files.createDirectories(root.resolve("uploads/123"));
        Path attachment = Files.writeString(owned.resolve("简历.html"),"hello");
        Path local = Files.writeString(workspace.resolve("报名.xml"),"<form/>");
        Path foreign = Files.writeString(Files.createDirectory(root.resolve("uploads/456")).resolve("other.txt"),"private");
        assertEquals(List.of(local.toRealPath(),attachment.toRealPath()), BrowserUploadFiles.resolve(workspace,owned,List.of("workspace/报名.xml","user-uploads/简历.html")));
        assertEquals(List.of(attachment.toRealPath()),BrowserUploadFiles.resolve(workspace,owned,List.of("user-uploads/简历.html?name=old-display.html")));
        assertThrows(SecurityException.class,()->BrowserUploadFiles.resolve(workspace,owned,List.of(foreign.toString())));
        assertThrows(SecurityException.class,()->BrowserUploadFiles.resolve(workspace,owned,List.of("../uploads/456/other.txt")));
        assertThrows(SecurityException.class,()->BrowserUploadFiles.resolve(workspace,owned,List.of(workspace.toString())));
    }
    @Test void rejectsDuplicatesMissingPathsAndOversizedTransfer() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path file = Files.writeString(workspace.resolve("one.txt"),"ok");
        assertThrows(IllegalArgumentException.class,()->BrowserUploadFiles.resolve(workspace,null,List.of("one.txt",file.toString())));
        assertThrows(IllegalArgumentException.class,()->BrowserUploadFiles.resolve(workspace,null,List.of()));
        assertThrows(java.io.IOException.class,()->BrowserUploadFiles.resolve(workspace,null,List.of("missing")));
        Path big = workspace.resolve("big.bin");
        try (var random = new java.io.RandomAccessFile(big.toFile(),"rw")) { random.setLength(BrowserUploadFiles.MAX_TOTAL_BYTES+1); }
        assertThrows(IllegalArgumentException.class,()->BrowserUploadFiles.resolve(workspace,null,List.of("big.bin")));
    }
}
