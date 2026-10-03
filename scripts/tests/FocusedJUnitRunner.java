import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Explicit private-output selection, never implicit scanning/native execution. */
public final class FocusedJUnitRunner {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Expected test-class and exact test-count");
        int expected = Integer.parseInt(args[1]);
        if (expected <= 0) throw new IllegalArgumentException("Test count must be positive");
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(args[0])).build());
        var summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out, true));
        summary.printFailuresTo(new java.io.PrintWriter(System.out, true));
        if (summary.getTestsFoundCount() != expected || summary.getTestsSucceededCount() != expected
                || summary.getTestsFailedCount() != 0 || summary.getTestsSkippedCount() != 0
                || !summary.getFailures().isEmpty()) System.exit(1);
    }
}
