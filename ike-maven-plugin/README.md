# ike-maven-plugin

**Documentation:** https://ike.network/ike-tooling/ike-maven-plugin/

The `ike:*` plugin — release orchestration, scaffolding, site deploy,
version upgrades, and build-time utilities for IKE Network projects.

## Common goals

```bash
mvn ike:release-status               # diagnose any in-flight release
mvn ike:release-draft                # preview a release
mvn ike:release-publish              # execute a release
mvn ike:deploy-site-publish -DsiteType=release
                                     # ad-hoc site re-deploy
mvn ike:scaffold-draft               # preview scaffold updates
mvn ike:versions-upgrade-draft       # preview version upgrades
```

See the [full module documentation](https://ike.network/ike-tooling/ike-maven-plugin/)
for the complete `ike:*` goal reference.
