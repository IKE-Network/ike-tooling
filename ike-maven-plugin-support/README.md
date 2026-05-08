# ike-maven-plugin-support

**Documentation:** https://ike.network/ike-tooling/ike-maven-plugin-support/

Shared library of helper classes used by the IKE Maven plugins
(`ike-maven-plugin` and `ike-workspace-maven-plugin`). Not a plugin
itself — has no mojos. Exists to keep the two plugins consistent
without one plugin depending on the other.

## Key classes

* `AbstractGoalMojo` — base class with markdown report writing.
* `GoalReport` — structured-markdown builder.
* `GoalRef` — type-safe goal reference (catches typos at compile time).
* `MojoParamSupport` — shared parameter parsing.
* `support.upgrade.*` — version-upgrade plan model.

See the [full module documentation](https://ike.network/ike-tooling/ike-maven-plugin-support/)
for the goal-registry pattern and module rationale.
