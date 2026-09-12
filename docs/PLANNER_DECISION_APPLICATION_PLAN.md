# Planner timing and common application

FedFirst, AggLocal, DP-Local and DP-Global return their validated selection through `PlacementPlanApplication`. It runs post-selection diagnostics, adapts the selection, normalizes it through the shared placement adapter, and invokes `PlacementEmissionTransaction`. Analysis identity, selected-plan authority and transaction validation remain mandatory.

DML reports common preparation separately. `PlannerPipelineTiming` partitions each successful planner invocation into Decision, Diagnostics, Conversion, Application and Finalization. Their integer nanoseconds sum to Total. The existing FedPlanner total remains available. Failed/incomplete invocations do not publish a successful timing record.

Decision includes physical model/cost construction, Local seed and bound refinement, exact search and validation of the selection. In-search trace work stays in Decision. Conversion contains the DP physical-to-placement projection; Application includes receipt validation. Finalization covers the compiler's final boundary verification.

Compare the four methods using `Compile Phase FedPlanner Decision` under the same trace settings; also report common preparation and the retained total when discussing compiler latency. Older measurements without phase records cannot supply Decision time.
