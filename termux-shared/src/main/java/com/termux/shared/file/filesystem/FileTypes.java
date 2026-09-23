package com.termux.shared.file.filesystem;

import android.system.Os;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;

import java.io.File;

public class FileTypes {

    /** Flags to represent regular, directory and symlink file types defined by {@link FileType} */
    public static final int FILE_TYPE_NORMAL_FLAGS = FileType.REGULAR.getValue() | FileType.DIRECTORY.getValue() | FileType.SYMLINK.getValue();

    /** Flags to represent any file type defined by {@link FileType} */
    public static final int FILE_TYPE_ANY_FLAGS = Integer.MAX_VALUE; // 1111111111111111111111111111111 (31 1's)

    public static String convertFileTypeFlagsToNamesString(int fileTypeFlags) {
        StringBuilder fileTypeFlagsStringBuilder = new StringBuilder();

        FileType[] fileTypes = {FileType.REGULAR, FileType.DIRECTORY, FileType.SYMLINK, FileType.CHARACTER, FileType.FIFO, FileType.BLOCK, FileType.UNKNOWN};
        for (FileType fileType : fileTypes) {
            if ((fileTypeFlags & fileType.getValue()) > 0)
                fileTypeFlagsStringBuilder.append(fileType.getName()).append(",");
        }

        String fileTypeFlagsString = fileTypeFlagsStringBuilder.toString();

        if (fileTypeFlagsString.endsWith(","))
            fileTypeFlagsString = fileTypeFlagsString.substring(0, fileTypeFlagsString.lastIndexOf(","));

        return fileTypeFlagsString;
    }

    /**
     * Checks the type of file that exists at {@code filePath}.
     *
     * Returns:
     * - {@link FileType#NO_EXIST} if {@code filePath} is {@code null}, empty, an exception is
     *      raised or no file exists at {@code filePath}.
     * - {@link FileType#REGULAR} / {@link FileType#DIRECTORY} / {@link FileType#SYMLINK} /
     *      {@link FileType#SOCKET} / {@link FileType#CHARACTER} / {@link FileType#FIFO} /
     *      {@link FileType#BLOCK} / {@link FileType#UNKNOWN} for the respective type;
     *      {@link FileType#SYMLINK} is only returned when {@code followLinks} is {@code false}.
     *
     * The {@link File} API is not reliable for this: {@link File#isFile()} and
     * {@link File#isDirectory()} use {@link Os#stat(String)} and follow symlinks, while
     * {@link File#exists()} uses {@link Os#access(String, int)} and returns {@code false} for
     * dangling symlinks on Android (https://stackoverflow.com/a/57747064/14686958). So we use
     * {@link Os#lstat(String)} when {@code followLinks} is {@code false} and
     * {@link Os#stat(String)} when it is {@code true}; all exceptions are treated as
     * non-existence.
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/java/io/File.java;l=793
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/native/UnixFileSystem_md.c;l=121
     * https://cs.android.com/android/_/android/platform/libcore/+/001ac51d61ad7443ba518bf2cf7e086efe698c6d
     *
     * @param filePath The {@code path} for file to check.
     * @param followLinks If {@code true}, the type of the symlink target; if {@code false}, the
     *                       type of the file at {@code filePath} itself.
     * @return Returns the {@link FileType} of file.
     */
    @NonNull
    public static FileType getFileType(final String filePath, final boolean followLinks) {
        if (filePath == null || filePath.isEmpty()) return FileType.NO_EXIST;

        try {
            FileAttributes fileAttributes = FileAttributes.get(filePath, followLinks);
            return getFileType(fileAttributes);
        } catch (Exception e) {
            // If not a ENOENT (No such file or directory) exception
            if (e.getMessage() != null && !e.getMessage().contains("ENOENT"))
                Logger.logError("Failed to get file type for file at path \"" + filePath + "\": " + e.getMessage());
            return FileType.NO_EXIST;
        }
    }

    public static FileType getFileType(@NonNull final FileAttributes fileAttributes) {
        if (fileAttributes.isRegularFile())
            return FileType.REGULAR;
        else if (fileAttributes.isDirectory())
            return FileType.DIRECTORY;
        else if (fileAttributes.isSymbolicLink())
            return FileType.SYMLINK;
        else if (fileAttributes.isSocket())
            return FileType.SOCKET;
        else if (fileAttributes.isCharacter())
            return FileType.CHARACTER;
        else if (fileAttributes.isFifo())
            return FileType.FIFO;
        else if (fileAttributes.isBlock())
            return FileType.BLOCK;
        else
            return FileType.UNKNOWN;
    }

}
