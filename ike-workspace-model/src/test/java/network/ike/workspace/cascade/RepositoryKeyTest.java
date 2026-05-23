package network.ike.workspace.cascade;

import org.apache.maven.api.model.Scm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link RepositoryKey} — the {@code <scm>}-derived
 * repository identity (IKE-Network/ike-issues#496 part C).
 */
class RepositoryKeyTest {

    @Test
    void normalises_plain_https_url() {
        assertThat(RepositoryKey.of(
                        "https://github.com/IKE-Network/ike-tooling").url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void strips_scm_provider_prefix() {
        assertThat(RepositoryKey.of(
                        "scm:git:https://github.com/IKE-Network/ike-tooling.git").url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void converts_ssh_shorthand_to_https() {
        assertThat(RepositoryKey.of(
                        "git@github.com:IKE-Network/ike-tooling.git").url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void converts_scm_prefixed_ssh_shorthand_to_https() {
        assertThat(RepositoryKey.of(
                        "scm:git:git@github.com:IKE-Network/ike-tooling.git").url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void converts_explicit_ssh_url_to_https() {
        assertThat(RepositoryKey.of(
                        "ssh://git@github.com/IKE-Network/ike-tooling.git").url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void strips_trailing_dot_git_and_slashes() {
        assertThat(RepositoryKey.of(
                        "https://github.com/IKE-Network/ike-tooling.git/").url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void all_url_forms_for_one_repo_compare_equal() {
        RepositoryKey a = RepositoryKey.of(
                "scm:git:https://github.com/IKE-Network/ike-tooling.git");
        RepositoryKey b = RepositoryKey.of(
                "git@github.com:IKE-Network/ike-tooling.git");
        RepositoryKey c = RepositoryKey.of(
                "https://github.com/IKE-Network/ike-tooling");
        RepositoryKey d = RepositoryKey.of(
                "ssh://git@github.com/IKE-Network/ike-tooling.git");

        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(d);
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
                .isEqualTo(c.hashCode()).isEqualTo(d.hashCode());
    }

    @Test
    void different_repos_compare_distinct() {
        assertThat(RepositoryKey.of(
                        "https://github.com/IKE-Network/ike-tooling"))
                .isNotEqualTo(RepositoryKey.of(
                        "https://github.com/IKE-Network/ike-docs"));
    }

    @Test
    void fromScm_prefers_url_when_both_url_and_connection_set() {
        Scm scm = Scm.newBuilder()
                .url("https://github.com/IKE-Network/ike-tooling")
                .connection("scm:git:https://github.com/wrong/repo.git")
                .build();

        assertThat(RepositoryKey.fromScm(scm).url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void fromScm_falls_back_to_connection_when_url_is_absent() {
        Scm scm = Scm.newBuilder()
                .connection("scm:git:https://github.com/IKE-Network/ike-tooling.git")
                .build();

        assertThat(RepositoryKey.fromScm(scm).url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void fromScm_returns_null_when_both_fields_blank_or_scm_is_null() {
        assertThat(RepositoryKey.fromScm(null)).isNull();
        assertThat(RepositoryKey.fromScm(Scm.newBuilder().build())).isNull();
        assertThat(RepositoryKey.fromScm(
                Scm.newBuilder().url(" ").connection(" ").build())).isNull();
    }

    @Test
    void null_or_blank_url_rejected() {
        assertThatThrownBy(() -> RepositoryKey.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryKey.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryKey.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
