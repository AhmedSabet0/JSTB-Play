package com.autoapply.bot.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Lets other apps (WhatsApp, Gmail, Drive...) read the files you share: the CSV, the log and debug.zip. Also gives
 *  your CV to a company's site you opened in the app, when you attach it to an application (cv/your-name.pdf). */
public final class FilesProvider extends ContentProvider {
    static final String AUTHORITY = BuildConfig.FILES_AUTHORITY;

    static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(f.getName()));
    }

    /** Your CV under the name you gave it (the site sees that name). */
    static Uri uriForCv(String displayName) {
        String n = displayName == null ? "" : displayName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (n.replace("_", "").isEmpty()) {
            n = "CV.pdf";
        }
        return Uri.parse("content://" + AUTHORITY + "/cv/" + Uri.encode(n));
    }

    /** Your profile photo / avatar when a form asks for a photo. */
    static Uri uriForPhoto() {
        return Uri.parse("content://" + AUTHORITY + "/photo/profile.jpg");
    }

    private static boolean isCv(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        return seg.size() == 2 && "cv".equals(seg.get(0));
    }

    private static boolean isPhoto(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        return seg.size() == 2 && "photo".equals(seg.get(0));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (isCv(uri)) {
            File cv = AndroidPlatform.get(getContext()).engine.config.cvFile(AndroidPlatform.get(getContext()).engine.config.load());
            if (cv == null || !cv.isFile()) {
                throw new FileNotFoundException("No CV");
            }
            return cv;
        }
        if (isPhoto(uri)) {
            File photo = new File(getContext().getFilesDir(), "profile_photo.jpg");
            if (!photo.isFile()) {
                throw new FileNotFoundException("No photo");
            }
            return photo;
        }
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
            throw new FileNotFoundException("Bad name");
        }
        File f = new File(new File(getContext().getFilesDir(), "logs"), name);
        if (!f.isFile()) {
            throw new FileNotFoundException(name);
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = fileFor(uri);
            String displayName = isCv(uri) ? uri.getLastPathSegment() : isPhoto(uri) ? "profile.jpg" : f.getName();
            c.addRow(new Object[]{displayName, f.length()});
        } catch (FileNotFoundException e) {
            // empty result
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        if (isPhoto(uri)) {
            return "image/jpeg";
        }
        String n = String.valueOf(uri.getLastPathSegment());
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg") || n.toLowerCase(java.util.Locale.ROOT).endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".csv")) {
            return "text/csv";
        }
        if (n.endsWith(".zip")) {
            return "application/zip";
        }
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
