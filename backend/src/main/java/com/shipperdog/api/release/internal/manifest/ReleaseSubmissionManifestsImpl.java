package com.shipperdog.api.release.internal.manifest;

import com.shipperdog.api.release.ReleaseSubmissionManifests;
import com.shipperdog.api.release.internal.repository.ReleaseManifestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link ReleaseSubmissionManifests} port's implementation (issue
 * #63, S13-03, AC1): the frozen store-submission block read back from
 * the STORED manifest document — never recomputed from live rows, the
 * manifest service's own read doctrine. The dispatch at the ACTIVE
 * crossing resolves this block into its command, so what a release
 * submits is by construction what its manifest froze.
 */
@Component
public class ReleaseSubmissionManifestsImpl implements ReleaseSubmissionManifests {

    private static final JsonMapper MAPPER = new JsonMapper();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReleaseSubmissionManifestsImpl.class);

    private final ReleaseManifestRepository manifests;

    ReleaseSubmissionManifestsImpl(ReleaseManifestRepository manifests) {
        this.manifests = manifests;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FrozenSubmission> frozenSubmissionOf(UUID releaseId) {
        return manifests.findByReleaseId(releaseId).flatMap(manifest -> {
            JsonNode block;
            try {
                block = MAPPER.readTree(manifest.getContent()).path("storeSubmission");
            } catch (RuntimeException e) {
                // A stored document is canonical JSON the platform
                // itself wrote; a parse fault here is storage
                // corruption, answered as "no block" loudly in the log.
                log.warn("the stored manifest of release {} failed to parse for its submission block", releaseId, e);
                return Optional.empty();
            }
            if (!block.isObject() || block.path("channel").asString(null) == null) {
                return Optional.empty();
            }
            List<FrozenLocale> locales = new ArrayList<>();
            for (JsonNode locale : block.path("locales")) {
                List<String> screenshotRefs = new ArrayList<>();
                for (JsonNode ref : locale.path("screenshotRefs")) {
                    screenshotRefs.add(ref.asText());
                }
                locales.add(new FrozenLocale(
                        locale.path("locale").asString(null),
                        locale.path("description").asString(null),
                        locale.path("keywords").asString(null),
                        List.copyOf(screenshotRefs),
                        locale.path("releaseNotes").asString(null)));
            }
            return Optional.of(new FrozenSubmission(
                    block.path("channel").asString(),
                    block.path("phasedRelease").asBoolean(false),
                    List.copyOf(locales)));
        });
    }
}
