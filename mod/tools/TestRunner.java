import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;

public final class TestRunner {
    public static void main(String[] args) {
        var builder = LauncherDiscoveryRequestBuilder.request();
        if (args.length == 0) builder.selectors(DiscoverySelectors.selectPackage("dev.newyear.countdown"));
        else for (String name : args) builder.selectors(DiscoverySelectors.selectClass(name));
        var request = builder.build();
        var listener=new SummaryGeneratingListener();
        LauncherFactory.create().execute(request,listener);
        listener.getSummary().printTo(new PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new PrintWriter(System.out));
        System.exit(listener.getSummary().getTestsFailedCount()==0&&listener.getSummary().getTestsSucceededCount()>0?0:1);
    }
}
