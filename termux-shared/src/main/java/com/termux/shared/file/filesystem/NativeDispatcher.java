package com.termux.shared.file.filesystem;

import android.system.ErrnoException;
import android.system.Os;

import java.io.IOException;

public class NativeDispatcher {

    public static void stat(String filePath, FileAttributes fileAttributes) throws IOException {
        validateFileExistence(filePath);

        try {
            fileAttributes.loadFromStructStat(Os.stat(filePath));
        } catch (ErrnoException e) {
            throw new IOException("Failed to run Os.stat() on file at path \"" + filePath + "\": " + e.getMessage());
        }
    }

    public static void lstat(String filePath, FileAttributes fileAttributes) throws IOException {
        validateFileExistence(filePath);

        try {
            fileAttributes.loadFromStructStat(Os.lstat(filePath));
        } catch (ErrnoException e) {
            throw new IOException("Failed to run Os.lstat() on file at path \"" + filePath + "\": " + e.getMessage());
        }
    }

    public static void validateFileExistence(String filePath) throws IOException {
        if (filePath == null || filePath.isEmpty()) throw new IOException("The path is null or empty");
    }

}
