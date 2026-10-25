package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process, and schedules the week's update check (once). */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Place.init(this);
        Updates.schedule(this);
    }
}
