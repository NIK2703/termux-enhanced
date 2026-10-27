package com.termux.app.attachments;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves SAF picker results to local paths for the terminal input. Picked files without a
 * local path are copied to {@code $HOME/.termux/tmp/attachments}; directories that are not
 * local are rejected with {@link DirectoryNotLocalException}.
 */
public final class AttachmentPicker {

    public static final int REQUEST_PICK_FILES = 2004;
    public static final int REQUEST_PICK_DIRECTORY = 2005;

    private static final String AUTHORITY_EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents";
    private static final String AUTHORITY_MEDIA = "com.android.providers.media.documents";
    private static final String DOWNLOADS_PUBLIC_URI = "content://downloads/public_downloads";
    private static final String ATTACHMENTS_DIR =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/tmp/attachments";
    private static final String DEFAULT_FILE_NAME = "attachment";
    private static final int MAX_FILE_NAME_LENGTH = 128;

    private AttachmentPicker() {
    }

    /** Opens the system document picker for multiple files. */
    @NonNull
    public static Intent createFilesIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    /** Opens the system document-tree picker for one directory. */
    @NonNull
    public static Intent createDirectoryIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    }

    /** Returns local paths for the picker result, preserving order and dropping duplicates. */
    @NonNull
    public static List<String> resolve(@NonNull Context context, @NonNull Intent data,
                                       boolean directory) throws IOException {
        if (directory) {
            Uri treeUri = data.getData();
            if (treeUri == null) return Collections.emptyList();

            String path = resolveTreePath(context, treeUri);
            if (path == null) throw new DirectoryNotLocalException();
            return Collections.singletonList(path);
        }

        Set<String> paths = new LinkedHashSet<>();
        for (Uri uri : getSelectedUris(data)) {
            String path = resolveFilePath(context, uri);
            if (path == null) path = copyToAttachments(context, uri);
            paths.add(path);
        }
        return new ArrayList<>(paths);
    }

    /** Joins paths for insertion as double-quoted shell arguments separated by single spaces. */
    @NonNull
    public static String joinPathsForInsertion(@NonNull List<String> paths) {
        StringBuilder joined = new StringBuilder();
        for (String path : paths) {
            if (joined.length() > 0) joined.append(' ');
            appendQuotedPath(joined, path);
        }
        return joined.toString();
    }

    /**
     * Appends a path enclosed in double quotes. Shell-special characters inside double quotes
     * are escaped so that the inserted argument always points at the selected file.
     */
    private static void appendQuotedPath(@NonNull StringBuilder out, @NonNull String path) {
        out.append('"');
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || c == '"' || c == '$' || c == '`') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
    }

    @NonNull
    private static List<Uri> getSelectedUris(@NonNull Intent data) {
        Set<Uri> uris = new LinkedHashSet<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        if (uris.isEmpty()) {
            Uri uri = data.getData();
            if (uri != null) uris.add(uri);
        }
        return new ArrayList<>(uris);
    }

    @Nullable
    private static String resolveFilePath(@NonNull Context context, @NonNull Uri uri) {
        String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            return readablePath(uri.getPath());
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)) return null;

        String authority = uri.getAuthority();
        if (authority == null) return null;

        // Termux's own document provider uses the absolute path as the document id.
        if (isTermuxDocumentsAuthority(context, authority)) {
            return readablePath(getDocumentId(uri));
        }

        // The share provider stores the real path in the URI path.
        if ((context.getPackageName() + ".files").equals(authority)) {
            return readablePath(uri.getPath());
        }

        if (!DocumentsContract.isDocumentUri(context, uri)) return null;

        String documentId = getDocumentId(uri);
        if (documentId == null) return null;
        String path = resolveDocumentId(context, authority, documentId, uri);
        return readablePath(path);
    }

    @Nullable
    private static String resolveTreePath(@NonNull Context context, @NonNull Uri treeUri) {
        String authority = treeUri.getAuthority();
        if (authority == null) return null;

        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (documentId == null || documentId.isEmpty()) return null;

        String path;
        if (isTermuxDocumentsAuthority(context, authority)) {
            path = documentId;
        } else if (AUTHORITY_EXTERNAL_STORAGE.equals(authority)) {
            path = resolveExternalStorageDocumentId(documentId);
        } else if (AUTHORITY_DOWNLOADS.equals(authority)) {
            path = resolveDownloadsDocumentId(context, documentId, treeUri);
        } else if (documentId.startsWith("/")) {
            path = documentId;
        } else {
            path = null;
        }

        if (path == null) return null;
        File directory = new File(path);
        return directory.isDirectory() && directory.canRead()
            ? directory.getAbsolutePath() : null;
    }

    @Nullable
    private static String resolveDocumentId(@NonNull Context context, @NonNull String authority,
                                             @NonNull String documentId, @NonNull Uri uri) {
        if (AUTHORITY_EXTERNAL_STORAGE.equals(authority)) {
            return resolveExternalStorageDocumentId(documentId);
        }
        if (AUTHORITY_DOWNLOADS.equals(authority)) {
            return resolveDownloadsDocumentId(context, documentId, uri);
        }
        if (AUTHORITY_MEDIA.equals(authority)) {
            return resolveMediaDocumentId(context, documentId, uri);
        }

        // Some third-party providers use absolute paths as document ids.
        if (documentId.startsWith("/")) return documentId;

        // Legacy _data column is a best-effort fallback; callers copy the stream if it is absent.
        return queryDataColumn(context, uri);
    }

    @Nullable
    private static String resolveExternalStorageDocumentId(@NonNull String documentId) {
        int separator = documentId.indexOf(':');
        if (separator < 0) return null;

        String volume = documentId.substring(0, separator);
        String relativePath = documentId.substring(separator + 1);

        File root;
        if ("primary".equalsIgnoreCase(volume)) {
            root = Environment.getExternalStorageDirectory();
        } else if ("home".equalsIgnoreCase(volume)) {
            // DocumentsUI's virtual "home" root is not a filesystem directory.
            return null;
        } else {
            root = new File("/storage", volume);
        }
        if (!root.isDirectory()) return null;

        return buildPathUnderRoot(root, relativePath);
    }

    @Nullable
    private static String buildPathUnderRoot(@NonNull File root, @NonNull String relativePath) {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File candidate = relativePath.isEmpty()
                ? canonicalRoot : new File(canonicalRoot, relativePath).getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            String candidatePath = candidate.getPath();
            if (!candidatePath.equals(rootPath)
                && !candidatePath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return candidatePath;
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static String resolveDownloadsDocumentId(@NonNull Context context,
                                                      @NonNull String documentId,
                                                      @NonNull Uri uri) {
        if (documentId.startsWith("raw:")) return documentId.substring(4);

        String data = queryDataColumn(context, uri);
        if (data != null) return data;

        if (documentId.startsWith("msf:")) {
            Long id = parseLong(documentId.substring(4));
            if (id == null) return null;
            return queryDataColumn(context,
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id));
        }

        Long id = parseLong(documentId);
        if (id == null) return null;
        return queryDataColumn(context,
            ContentUris.withAppendedId(Uri.parse(DOWNLOADS_PUBLIC_URI), id));
    }

    @Nullable
    private static String resolveMediaDocumentId(@NonNull Context context,
                                                 @NonNull String documentId,
                                                 @NonNull Uri uri) {
        String data = queryDataColumn(context, uri);
        if (data != null) return data;

        int separator = documentId.indexOf(':');
        if (separator < 0) return null;

        String type = documentId.substring(0, separator);
        Long id = parseLong(documentId.substring(separator + 1));
        if (id == null) return null;

        Uri collection;
        switch (type) {
            case "image":
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                break;
            case "video":
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                break;
            case "audio":
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                break;
            default:
                collection = MediaStore.Files.getContentUri("external");
                break;
        }
        return queryDataColumn(context, ContentUris.withAppendedId(collection, id));
    }

    @Nullable
    private static Long parseLong(@NonNull String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String queryDataColumn(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
            uri, new String[]{"_data"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex("_data");
                if (column >= 0) {
                    String path = cursor.getString(column);
                    if (path != null && !path.isEmpty()) return path;
                }
            }
        } catch (Exception ignored) {
            // Providers may not expose _data; the caller copies the stream instead.
        }
        return null;
    }

    @NonNull
    private static String copyToAttachments(@NonNull Context context, @NonNull Uri uri)
        throws IOException {
        File directory = new File(ATTACHMENTS_DIR);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create attachment directory: " + directory);
        }

        File target = uniqueFile(directory, sanitizeFileName(queryDisplayName(context, uri)));
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new FileNotFoundException("Cannot open " + uri);
            try (OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Do not leave a truncated file behind if the copy fails.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
        return target.getAbsolutePath();
    }

    @NonNull
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
            uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }

        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.isEmpty() ? DEFAULT_FILE_NAME : fallback;
    }

    /** Sanitizes a provider display name so it cannot escape the attachments directory. */
    @NonNull
    static String sanitizeFileName(@Nullable String name) {
        if (name == null || name.isEmpty()) return DEFAULT_FILE_NAME;

        String cleaned = name.replace('/', '_')
            .replace('\\', '_')
            .replace('\0', '_')
            .replace('\n', '_')
            .replace('\r', '_')
            .replace('\t', '_')
            .trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return DEFAULT_FILE_NAME;
        }

        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            String extension = dot > 0 && cleaned.length() - dot <= 16
                ? cleaned.substring(dot) : "";
            int baseLength = Math.max(1, MAX_FILE_NAME_LENGTH - extension.length());
            cleaned = cleaned.substring(0, baseLength) + extension;
        }
        return cleaned;
    }

    @NonNull
    private static File uniqueFile(@NonNull File directory, @NonNull String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;

        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 2; i < 10000; i++) {
            candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, System.currentTimeMillis() + "-" + name);
    }

    @Nullable
    private static String readablePath(@Nullable String path) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        return file.canRead() ? file.getAbsolutePath() : null;
    }

    @Nullable
    private static String getDocumentId(@NonNull Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isTermuxDocumentsAuthority(@NonNull Context context,
                                                       @NonNull String authority) {
        return (context.getPackageName() + ".documents").equals(authority);
    }

    /** Thrown when a picked directory has no local filesystem path. */
    public static final class DirectoryNotLocalException extends IOException {
        public DirectoryNotLocalException() {
            super("The selected directory is not available as a local path");
        }
    }
}
