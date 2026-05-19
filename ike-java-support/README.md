# ike-java-support

**Documentation:** https://ike.network/ike-tooling/ike-java-support/

JDK-only generic Java utilities shared across the IKE Maven plugins
(`ike-maven-plugin`, `ike-workspace-maven-plugin`, `ike-doc-maven-plugin`).

Hard invariant: **no non-JDK dependencies.** Anything that needs an
external library belongs in a separate `ike-<dep>-support` module, so
this one stays a zero-dependency leaf every plugin reactor can sit on.

## Core types

* `ConstantBackedEnum` (`network.ike.support.enums`) — pairs each enum
  constant with a matched `public static final String NAME_*` mirror
  field and verifies the one-to-one correspondence at class-load. Java
  requires annotation element values to be constant expressions, which
  an enum reference is not; the mirror constant is. This lets an
  enum-backed name drive a `@Mojo(name = Goal.NAME_X)` annotation while
  the enum remains the single source of truth — a rename that breaks
  the pairing fails the build at class-load instead of silently
  miscompiling.

See the [full module documentation](https://ike.network/ike-tooling/ike-java-support/).
