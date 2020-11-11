package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;

import androidx.preference.PreferenceManager;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.users.FullAccount;
import com.dropbox.core.v2.files.ListFolderResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;


public class Dropbox {
    // Define default folder to store data & delay to check updates (OI: make config option)
    private static String TXT_FOLDER = "txt_files";
    private static Integer UPDATE_DELAY = 5*60*1000; // in [ms]

    private static class DropboxTask extends AsyncTask<Context, Void, Void>
    {
        @Override
        protected Void doInBackground(Context ... context) {
            // Update variables
            String LOCAL_FOLDER = context[0].getFilesDir().toString();

            // Obtain access code from preferences
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context[0]);
            String ACCESS_TOKEN_OAUTH = preferences.getString(SettingsActivity.DROPBOX_OAUTH_KEY, SettingsActivity.DROPBOX_OAUTH_KEY_DEFAULT);

            // Create Dropbox client connection
            DbxRequestConfig config = DbxRequestConfig.newBuilder("dropbox/KiepKeyboard").build();
            DbxClientV2 client = new DbxClientV2(config, ACCESS_TOKEN_OAUTH);

            // Check if connection is working and whether account can be read properly
            try { FullAccount account = client.users().getCurrentAccount(); }
            catch (DbxException e) { e.printStackTrace();  return null;}

            // Check if the directory with text files already exists on Dropbox
            try { client.files().listFolder("/"+TXT_FOLDER); }
            catch (DbxException e)
            {
                // No folder found; try to create it
                try { client.files().createFolderV2("/"+TXT_FOLDER); }
                catch (DbxException f) { f.printStackTrace(); }
            }

            // Check if the directory local on the device already exists
            File local_folder = new File(LOCAL_FOLDER+"/"+TXT_FOLDER);
            // Create directory if it does not exist yet
            if (!local_folder.exists()) { local_folder.mkdir(); }
            else if (!local_folder.isDirectory()) {
                // Display an error that it is not a directory OI
            }

            // Get files and folder metadata from Dropbox directory, and end with a sleep
            try {
                while (true) {
                    // Gather the list with files
                    ListFolderResult result = null;
                    result = client.files().listFolder("/" + TXT_FOLDER);
                    // Run over the files available
                    for (int i = 0; i < result.getEntries().size(); i++) {
                        // Get the filename
                        String filename_dropbox = result.getEntries().get(i).getName();
                        if (filename_dropbox.matches("F\\d+.txt")) {
                            // File is a F1.txt-F12.txt file -> sync to local
                            OutputStream downloadFile = new FileOutputStream(local_folder.toString() + "/" + filename_dropbox);
                            FileMetadata downloadBuilder = client.files().downloadBuilder("/" + TXT_FOLDER + "/" + filename_dropbox).download(downloadFile);
                        }
                    }
                    // Perform a delay of the thread
                    Thread.sleep(UPDATE_DELAY);
                }
            } catch (DbxException | IOException | InterruptedException l) {
                l.printStackTrace();
                return null;
            }
        }
    }

    public Dropbox(Context context) {
        // Obtain the folder to store data locally
        new DropboxTask().execute(context);
    }
}