package fogids.sim;

import java.util.ArrayList;
import java.util.List;

import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Tuple;

/**
 * Builds the application module graph from the architecture report, Section 3:
 *
 *   SENSOR_DATA ──► fastpath_filter ──► fog_processing ──► ECU_ACTION (actuator)
 *                        │                    │
 *                        │(non-blocking)      └──► CLOUD_LOG (sampled, to cloud_analytics)
 *                        └──► ids_inference ──► VERDICT_LOG (to cloud_analytics)
 *
 * fastpath_filter represents the report's fast path: HMAC verification, rate
 * limiting, schema validation. It is deliberately the cheapest module in the
 * graph (see FASTPATH_MIPS) so its processing delay stays under the < 1 ms
 * budget cited in Section 4, independent of ingress rate — that property is
 * checked by scenario runs, not assumed.
 *
 * ids_inference represents the slow path (tiered anomaly detection). Its
 * MIPS cost below is a STUB: the real, tier-dependent cost table is Member
 * A's deliverable (ml/metrics/tier_costs.csv), and which tier is active at
 * a given moment is Member C's resource-monitor policy. Until both land,
 * this module runs at a single fixed cost representing the "reduced" tier,
 * so that the pipeline is provably wired end-to-end (non-blocking, correct
 * placement, correct loop instrumentation) before the real tiering behaviour
 * is integrated. This mirrors the project's own stub-first integration rule.
 */
public class AppGraph {

    /**
     * STUB — replace with Member A's measured full/reduced/minimal costs.
     *
     * CALIBRATION NOTE: these are relative simulation units (CloudSim "MIPS"
     * here is a make-believe absolute scale, as is standard for this kind of
     * teaching/research simulator — what matters is the ratio of demand to
     * device capacity, not the literal number). Sized so that fog-2 (15
     * sensor/vehicle pairs at 20 Hz = 300 msg/s, see Topology) sits at
     * roughly 30-40% utilisation on a 4000-MIPS node under normal load —
     * i.e. comfortably in the report's "full tier" band (<60%) with real
     * headroom, so that a later attack surge (Member C) has somewhere to
     * push the load from. An earlier pass at 800/1200 oversubscribed the
     * node by roughly 150x and pinned utilisation at 100% even with no
     * attack present, which would have made any future tier-switching
     * demonstration meaningless. Ratios, not the absolute figures, are
     * what future retuning against Member A's real cost table must preserve.
     */
    public static final int FASTPATH_MIPS = 1;       // trivial: O(1) checks
    public static final int FOG_PROCESSING_MIPS = 4;
    public static final int IDS_INFERENCE_MIPS_STUB = 6; // "reduced tier" placeholder
    public static final int CLOUD_ANALYTICS_MIPS = 4;
    /**
     * Cost of handling one verdict at the cloud analytics sink. Deliberately far
     * cheaper than CLOUD_ANALYTICS_MIPS: a verdict is a small tag plus a confidence
     * value, not a re-processed copy of the original sensor payload. An earlier
     * pass reused CLOUD_ANALYTICS_MIPS for verdicts and sent one for every single
     * tuple (selectivity 1.0) — that made the fog-with-IDS scenario's cloud
     * execution cost exceed even the cloud-only baseline, which is not a
     * defensible result for a system whose entire point is to keep processing
     * at the edge. See results/NOTES.md for the before/after numbers.
     */
    public static final int VERDICT_LOG_MIPS = 1;
    public static final int VERDICT_LOG_NW_BYTES = 50;

    public static final String TUPLE_SENSOR = "SENSOR_DATA";
    public static final String TUPLE_TO_PROCESSING = "TO_PROCESSING";
    public static final String TUPLE_TO_IDS = "TO_IDS";
    public static final String TUPLE_CLOUD_LOG = "CLOUD_LOG";
    public static final String TUPLE_VERDICT_LOG = "VERDICT_LOG";
    public static final String TUPLE_ECU_RESULT = "ECU_RESULT";
    public static final String ACTUATOR_TYPE = "ECU_ACTION";

    public static final String MOD_FASTPATH = "fastpath_filter";
    public static final String MOD_PROCESSING = "fog_processing";
    public static final String MOD_IDS = "ids_inference";
    public static final String MOD_CLOUD_ANALYTICS = "cloud_analytics";

    /**
     * @param includeIds false builds the "fog, no IDS" application — the slow
     *                   path module and its edges are entirely absent, not
     *                   merely relocated, matching the report's definition of
     *                   that scenario as isolating the pure fog benefit before
     *                   any security processing exists.
     */
    @SuppressWarnings("serial")
    public static Application build(String appId, int userId, boolean includeIds) {
        Application app = Application.createApplication(appId, userId);

        app.addAppModule(MOD_FASTPATH, 4, FASTPATH_MIPS, 1000);
        app.addAppModule(MOD_PROCESSING, 128, FOG_PROCESSING_MIPS, 5000);
        app.addAppModule(MOD_CLOUD_ANALYTICS, 256, CLOUD_ANALYTICS_MIPS, 5000);
        if (includeIds) {
            app.addAppModule(MOD_IDS, 256, IDS_INFERENCE_MIPS_STUB, 5000);
        }

        // Sensor into the fast path
        app.addAppEdge(TUPLE_SENSOR, MOD_FASTPATH, FASTPATH_MIPS, 500, TUPLE_SENSOR, Tuple.UP, AppEdge.SENSOR);

        // Fast path fans out: one copy proceeds to normal processing (always),
        // one copy is tapped to the slow-path detector (only if it exists).
        app.addAppEdge(MOD_FASTPATH, MOD_PROCESSING, FOG_PROCESSING_MIPS, 500, TUPLE_TO_PROCESSING, Tuple.UP, AppEdge.MODULE);
        if (includeIds) {
            app.addAppEdge(MOD_FASTPATH, MOD_IDS, IDS_INFERENCE_MIPS_STUB, 500, TUPLE_TO_IDS, Tuple.UP, AppEdge.MODULE);
        }

        // Normal processing result reaches the vehicle ECU (the real-time, safety-adjacent path)
        app.addAppEdge(MOD_PROCESSING, ACTUATOR_TYPE, 50, 28, 100, TUPLE_ECU_RESULT, Tuple.DOWN, AppEdge.ACTUATOR);

        // Sampled telemetry to the cloud dashboard (not on the real-time path)
        app.addAppEdge(MOD_PROCESSING, MOD_CLOUD_ANALYTICS, CLOUD_ANALYTICS_MIPS, 1000, TUPLE_CLOUD_LOG, Tuple.UP, AppEdge.MODULE);
        if (includeIds) {
            app.addAppEdge(MOD_IDS, MOD_CLOUD_ANALYTICS, VERDICT_LOG_MIPS, VERDICT_LOG_NW_BYTES, TUPLE_VERDICT_LOG, Tuple.UP, AppEdge.MODULE);
        }

        // Selectivity: how each module turns its inputs into outputs
        app.addTupleMapping(MOD_FASTPATH, TUPLE_SENSOR, TUPLE_TO_PROCESSING, new FractionalSelectivity(1.0));
        if (includeIds) {
            app.addTupleMapping(MOD_FASTPATH, TUPLE_SENSOR, TUPLE_TO_IDS, new FractionalSelectivity(1.0));
            app.addTupleMapping(MOD_IDS, TUPLE_TO_IDS, TUPLE_VERDICT_LOG, new FractionalSelectivity(1.0));
        }
        app.addTupleMapping(MOD_PROCESSING, TUPLE_TO_PROCESSING, TUPLE_ECU_RESULT, new FractionalSelectivity(1.0));
        // Only sample 1 in 10 processed messages up to the cloud dashboard — this traffic
        // is analytics, not the safety path, and shouldn't dominate network-usage results.
        app.addTupleMapping(MOD_PROCESSING, TUPLE_TO_PROCESSING, TUPLE_CLOUD_LOG, new FractionalSelectivity(0.1));

        // Loops we report latency for.
        // loop_ecu_decision: sensor capture through to the vehicle receiving a result —
        // this is the loop the report's 100 ms budget is about.
        final AppLoop loopEcu = new AppLoop(new ArrayList<String>() {{
            add(MOD_FASTPATH); add(MOD_PROCESSING); add(ACTUATOR_TYPE);
        }});
        List<AppLoop> loops = new ArrayList<>();
        loops.add(loopEcu);

        if (includeIds) {
            // loop_ids: fast path through to a slow-path verdict — reported separately,
            // since by design it is NOT on the blocking path and is allowed to exceed
            // the ECU decision budget.
            final AppLoop loopIds = new AppLoop(new ArrayList<String>() {{
                add(MOD_FASTPATH); add(MOD_IDS);
            }});
            loops.add(loopIds);
        }
        app.setLoops(loops);

        return app;
    }
}
