package network.ike.plugin.adoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Which lines count as an include through the attribute, and which do not. */
class IncludeScannerTest {

    @TempDir
    Path temp;

    @Test
    void includesThroughTheAttributeOrItsAliasAreFoundWithTheirLine() throws Exception {
        AdocFixtures.write(temp.resolve("book.adoc"), """
                = Book

                include::{adoc}/network.ike.lab/dev/gitflow-overview.adoc[leveloffset=+1]
                include::{topics}/topics/dev/old.adoc[]
                \\include::{adoc}/escaped.adoc[]
                // include::{adoc}/commented.adoc[]
                ////
                include::{adoc}/in-comment-block.adoc[]
                ////
                include::chapters/relative.adoc[]
                  include::{adoc}/network.ike.lab/dev/{name}.adoc[]
                """);
        AdocFixtures.write(temp.resolve("notes.txt"), "include::{adoc}/not-adoc.adoc[]\n");

        assertThat(IncludeScanner.scan(temp)).containsExactly(
                new IncludeReference(temp.resolve("book.adoc"), 3, "adoc", "network.ike.lab/dev/gitflow-overview.adoc"),
                new IncludeReference(temp.resolve("book.adoc"), 4, "topics", "topics/dev/old.adoc"),
                new IncludeReference(temp.resolve("book.adoc"), 11, "adoc", "network.ike.lab/dev/{name}.adoc"));
    }

    @Test
    void aMissingRootYieldsNothing() {
        assertThat(IncludeScanner.scan(temp.resolve("absent"))).isEmpty();
    }
}
