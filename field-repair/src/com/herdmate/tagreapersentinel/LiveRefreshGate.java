package com.herdmate.tagreapersentinel;

import android.os.Handler;

/** Main-thread only. Coalesces display work; never filters RFID or scale evidence. */
public final class LiveRefreshGate implements Runnable {
    private final Handler handler;
    private final Runnable refresh;
    private boolean pending;

    public LiveRefreshGate(Handler handler, Runnable refresh) {
        this.handler = handler;
        this.refresh = refresh;
    }

    public void request() {
        if (pending) return;
        pending = true;
        if (!handler.postDelayed(this, 250L)) pending = false;
    }

    @Override public void run() {
        pending = false;
        refresh.run();
    }
}
