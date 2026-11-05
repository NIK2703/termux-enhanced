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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves SAF picker results to local paths for the terminal input. Items without a readable
 * local path are rejected with {@link FileNotLocalException} or {@link DirectoryNotLocalException}.
 */
public final class AttachmentPicker {

    public static final int REQUEST_PICK_FILES = 2004;
    public static final int REQUEST_PICK_DIRECTORY = 2005;

    private static final String AUTHORITY_EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents";
    private static final String AUTHORITY_MEDIA = "com.android.providers.media.documents";
    private static final String DOWNLOADS_PUBLIC_URI = "content://downloads/public_downloads";

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

    /**
     * Returns local paths for the picker result, preserving order and dropping duplicates.
     *
     * @throws FileNotLocalException if a selected file has no readable local path.
     * @throws DirectoryNotLocalException if the selected directory has no local path.
     */
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
            if (path == null) throw new FileNotLocalException(uri);
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

        if (!DocumentsContract.isDocumentUri(context, uri)) {
            return readablePath(queryDataColumn(context, uri));
        }

        String documentId = getDocumentId(uri);
        if (documentId == null) return null;
        String path = readablePath(resolveDocumentId(context, authority, documentId));
        if (path == null) path = readablePath(queryDataColumn(context, uri));
        return path;
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
            path = resolveDownloadsDocumentId(context, documentId);
        } else if (documentId.startsWith("/")) {
            path = documentId;
        } else {
            path = null;
        }

        if (path == null) path = queryDataColumn(context, treeUri);

        if (path == null) return null;
        File directory = new File(path);
        return directory.isDirectory() && directory.canRead()
            ? directory.getAbsolutePath() : null;
    }

    @Nullable
    private static String resolveDocumentId(@NonNull Context context, @NonNull String authority,
                                             @NonNull String documentId) {
        if (AUTHORITY_EXTERNAL_STORAGE.equals(authority)) {
            return resolveExternalStorageDocumentId(documentId);
        }
        if (AUTHORITY_DOWNLOADS.equals(authority)) {
            return resolveDownloadsDocumentId(context, documentId);
        }
        if (AUTHORITY_MEDIA.equals(authority)) {
            return resolveMediaDocumentId(context, documentId);
        }

        // Some third-party providers use absolute paths as document ids.
        if (documentId.startsWith("/")) return documentId;

        return null;
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
    static String buildPathUnderRoot(@NonNull File root, @NonNull String relativePath) {
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
                                                      @NonNull String documentId) {
        if (documentId.startsWith("raw:")) return documentId.substring(4);

        if (documentId.startsWith("msf:")) {
            Long id = parseLong(documentId.substring(4));
            return id == null ? null : queryMediaStoreDataColumn(context, id);
        }

        Long id = parseLong(documentId);
        if (id == null) return null;

        // Legacy DownloadManager ids are not MediaStore ids; only the old provider URI maps
        // them safely. It is gone on recent Android versions.
        return queryDataColumn(context,
            ContentUris.withAppendedId(Uri.parse(DOWNLOADS_PUBLIC_URI), id));
    }

    @Nullable
    private static String resolveMediaDocumentId(@NonNull Context context,
                                                 @NonNull String documentId) {
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
    private static String queryMediaStoreDataColumn(@NonNull Context context, long id) {
        return queryDataColumn(context,
            ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id));
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
            // _data is optional and may be unavailable.
        }
        return null;
    }

    @Nullable
    static String readablePath(@Nullable String path) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        return file.isFile() && file.canRead() ? file.getAbsolutePath() : null;
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

    /** Thrown when a picked file has no readable local filesystem path. */
    public static final class FileNotLocalException extends IOException {
        public FileNotLocalException(@NonNull Uri uri) {
            super("The selected file is not available as a local path: " + uri);
        }
    }

    /** Thrown when a picked directory has no local filesystem path. */
    public static final class DirectoryNotLocalException extends IOException {
        public DirectoryNotLocalException() {
            super("The selected directory is not available as a local path");
        }
    }
}
