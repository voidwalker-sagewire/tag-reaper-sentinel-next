package android.os;
import java.util.ArrayDeque;
public class Handler {
    public final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    public boolean accept = true;
    public long delay;
    public int posts;
    public boolean postDelayed(Runnable task, long delay) {
        if (!accept) return false;
        this.delay = delay;
        queue.add(task);
        posts++;
        return true;
    }
    public void tick() { queue.remove().run(); }
}
