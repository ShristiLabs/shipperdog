package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import com.shipperdog.api.tenant.internal.repository.TenantMemberScopedRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Repository-layer guard tests of the tenant-isolation seam (INV-001,
 * issue #10, acceptance criteria 1, 2, and 4) against a real
 * PostgreSQL: two tenants with seeded members, one context at a time.
 * Every tenant-scoped query is filtered by the bound context without
 * the caller asking for it, a missing context fails closed, cross-tenant
 * reads surface as empty results, cross-tenant writes are refused, and
 * the platform-administrator override is the explicit, working
 * exception.
 */
@SpringBootTest(properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class TenantIsolationRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final UUID ORGANIZATION = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("30000000-0000-4000-8000-000000000010");
    private static final UUID TENANT_B = UUID.fromString("30000000-0000-4000-8000-000000000020");
    private static final UUID USER_A1 = UUID.fromString("30000000-0000-4000-8000-000000000011");
    private static final UUID USER_A2 = UUID.fromString("30000000-0000-4000-8000-000000000012");
    private static final UUID USER_B1 = UUID.fromString("30000000-0000-4000-8000-000000000021");

    /** Membership row ids captured at seed time for cross-tenant id probes. */
    private UUID membershipA1;

    private UUID membershipA2;

    private UUID membershipB1;

    @Autowired
    private TenantMemberScopedRepository members;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO organizations (id, name, external_id) VALUES (?, 'Isolation Org', 'iso-org-1')",
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'Tenant Alpha', 'iso-a')",
                TENANT_A,
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'Tenant Beta', 'iso-b')",
                TENANT_B,
                ORGANIZATION);
        for (UUID user : List.of(USER_A1, USER_A2, USER_B1)) {
            jdbc.update(
                    "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Isolation User')",
                    user,
                    "iso-" + user + "@example.com");
        }
        jdbc.update(
                "INSERT INTO tenant_members (id, tenant_id, user_id, role) VALUES (?, ?, ?, 'TENANT_ADMIN')",
                UUID.fromString("30000000-0000-4000-8000-000000000101"),
                TENANT_A,
                USER_A1);
        jdbc.update(
                "INSERT INTO tenant_members (id, tenant_id, user_id, role) VALUES (?, ?, ?, 'TENANT_TESTER')",
                UUID.fromString("30000000-0000-4000-8000-000000000102"),
                TENANT_A,
                USER_A2);
        jdbc.update(
                "INSERT INTO tenant_members (id, tenant_id, user_id, role) VALUES (?, ?, ?, 'TENANT_ADMIN')",
                UUID.fromString("30000000-0000-4000-8000-000000000201"),
                TENANT_B,
                USER_B1);
        membershipA1 = membershipIdOf(TENANT_A, USER_A1);
        membershipA2 = membershipIdOf(TENANT_A, USER_A2);
        membershipB1 = membershipIdOf(TENANT_B, USER_B1);
    }

    private UUID membershipIdOf(UUID tenantId, UUID userId) {
        return jdbc.queryForObject(
                "SELECT id FROM tenant_members WHERE tenant_id = ? AND user_id = ?", UUID.class, tenantId, userId);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.end();
        jdbc.update("DELETE FROM tenant_members WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", USER_A1, USER_A2, USER_B1);
        jdbc.update("DELETE FROM organizations WHERE id = ?", ORGANIZATION);
    }

    @Test
    void queriesAreFilteredByTheBoundContextWithoutBeingAsked() {
        TenantContext.begin(TENANT_A, USER_A1, false);
        Page<TenantMemberEntity> rows = members.findAllScoped(Specification.unrestricted(), offsetZero());
        assertThat(rows.getContent())
                .as("context A sees exactly A's members, never B's")
                .hasSize(2)
                .allSatisfy(membership -> assertThat(membership.getTenantId()).isEqualTo(TENANT_A));

        TenantContext.end();
        TenantContext.begin(TENANT_B, USER_B1, false);
        Page<TenantMemberEntity> other = members.findAllScoped(Specification.unrestricted(), offsetZero());
        assertThat(other.getContent())
                .as("context B sees exactly B's members")
                .hasSize(1)
                .allSatisfy(membership -> assertThat(membership.getTenantId()).isEqualTo(TENANT_B));
    }

    @Test
    void crossTenantFetchByIdIsEmptyLikeAGenuineMiss() {
        TenantContext.begin(TENANT_B, USER_B1, false);
        assertThat(members.findScoped(membershipA1))
                .as("B's context cannot load A's membership by id (issue #10, acceptance criterion 2)")
                .isEmpty();
        assertThat(members.findScoped(UUID.randomUUID())).isEmpty();
    }

    @Test
    void filteredLookupsStayInsideTheContextToo() {
        TenantContext.begin(TENANT_B, USER_B1, false);
        assertThat(members.findOneScoped(userEquals(USER_A1)))
                .as("asking for A's user inside B's context finds nothing")
                .isEmpty();
        assertThat(members.existsScoped(userEquals(USER_A1))).isFalse();
        assertThat(members.findOneScoped(userEquals(USER_B1))).isPresent();
        assertThat(members.existsScoped(userEquals(USER_B1))).isTrue();
    }

    @Test
    void withoutContextEveryOperationFailsClosed() {
        assertThatThrownBy(() -> members.findAllScoped(Specification.unrestricted(), offsetZero()))
                .isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(() -> members.findScoped(membershipA1)).isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(() -> members.findOneScoped(userEquals(USER_A1)))
                .isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(() -> members.existsScoped(userEquals(USER_A1)))
                .isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(
                        () -> members.saveScoped(new TenantMemberEntity(TENANT_A, USER_B1, TenantRole.TENANT_TESTER)))
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void crossTenantWritesAreRefused() {
        TenantContext.begin(TENANT_B, USER_B1, false);
        TenantMemberEntity foreignRow = new TenantMemberEntity(TENANT_A, USER_B1, TenantRole.TENANT_TESTER);
        assertThatThrownBy(() -> members.saveScoped(foreignRow))
                .as("context B must not write into tenant A (INV-001)")
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("write refused");
        Integer written = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                Integer.class,
                TENANT_A,
                USER_B1);
        assertThat(written).as("the refused write persisted nothing").isZero();
    }

    @Test
    void platformOverrideIsTheExplicitCrossTenantWritePath() {
        TenantContext.begin(TENANT_B, UUID.randomUUID(), true);
        TenantMemberEntity foreignRow = new TenantMemberEntity(TENANT_A, USER_B1, TenantRole.TENANT_TESTER);
        TenantMemberEntity saved = members.saveScoped(foreignRow);
        assertThat(saved.getTenantId())
                .as("the platform-admin override authorizes cross-tenant administration (issue #10, AC4)")
                .isEqualTo(TENANT_A);
        assertThat(members.findScoped(saved.getId()))
                .as("reads stay context-scoped even under the override")
                .isEmpty();
    }

    @Test
    void listingAdoptsThePlatformOrderingConvention() {
        TenantContext.begin(TENANT_A, USER_A1, false);
        Page<TenantMemberEntity> page = members.findAllScoped(Specification.unrestricted(), offsetZero());
        assertThat(page.getContent())
                .as("unsorted pageables order by createdAt asc, id asc (issue #8 convention)")
                .extracting(TenantMemberEntity::getUserId)
                .containsExactly(USER_A1, USER_A2);
    }

    /**
     * The inherited JpaRepository surface is policy, not a bypass (issue
     * #10 fix, ADR-0002 amendment): id-based reads behave scoped — an id
     * of another tenant is indistinguishable from an unknown id — and
     * the by-id count is the context tenant's count.
     */
    @Test
    void inheritedIdBasedReadsBehaveScoped() {
        TenantContext.begin(TENANT_B, USER_B1, false);
        assertThat(members.findById(membershipA1))
                .as("a foreign id reads as a genuine miss (PRD 12)")
                .isEmpty();
        assertThat(members.findById(membershipB1)).isPresent();
        assertThat(members.findAllById(List.of(membershipA1, membershipB1)))
                .as("only the context tenant's rows come back")
                .extracting(TenantMemberEntity::getUserId)
                .containsExactly(USER_B1);
        assertThat(members.existsById(membershipA1)).isFalse();
        assertThat(members.existsById(membershipB1)).isTrue();
        assertThat(members.count()).as("count is context-scoped").isEqualTo(1);
    }

    /**
     * The inherited single-row write shapes carry the same guard as the
     * blessed scoped operations: a foreign-tenant entity is refused
     * (also for deletes, where a cross-tenant touch is a write, not a
     * miss), a context-tenant entity persists or removes — the verified
     * {@code delete(entity)} path {@code TenantMemberService.removeMember}
     * relies on.
     */
    @Test
    void inheritedSingleRowWritesAreTenantGuarded() {
        TenantContext.begin(TENANT_A, USER_A1, false);
        assertThatThrownBy(() -> members.save(new TenantMemberEntity(TENANT_B, USER_A1, TenantRole.TENANT_TESTER)))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("write refused");
        assertThat(membershipCount(TENANT_B, USER_A1)).isZero();

        members.save(new TenantMemberEntity(TENANT_A, USER_B1, TenantRole.TENANT_TESTER));
        assertThat(membershipCount(TENANT_A, USER_B1))
                .as("a context-tenant entity persists through the guarded inherited save")
                .isEqualTo(1);

        members.delete(members.findScoped(membershipA2).orElseThrow());
        assertThat(members.findById(membershipA2))
                .as("the verified delete(entity) path removes the context tenant's row (removeMember)")
                .isEmpty();

        assertThatThrownBy(() -> members.delete(new TenantMemberEntity(TENANT_B, USER_A1, TenantRole.TENANT_TESTER)))
                .as("deleting a foreign-tenant entity is refused, never a silent no-op")
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("write refused");
        assertThat(membershipCount(TENANT_A, USER_A1)).isEqualTo(1);
    }

    /**
     * {@code deleteById} keeps Spring Data semantics but scoped: an id of
     * another tenant, like an unknown id, deletes nothing and stays
     * silent about why; a context-tenant id removes its row.
     */
    @Test
    void inheritedDeleteByIdRemovesOnlyTheContextTenantsRows() {
        TenantContext.begin(TENANT_B, USER_B1, false);
        members.deleteById(membershipA1);
        assertThat(membershipCount(TENANT_A, USER_A1))
                .as("a foreign id must not delete another tenant's row")
                .isEqualTo(1);
        members.deleteById(membershipB1);
        assertThat(members.existsById(membershipB1)).isFalse();
    }

    /**
     * The inherited operations with no tenant-safe meaning refuse with
     * {@link TenantIsolationException} — bulk destruction, unparameterized
     * listings, lazy proxies, and the whole {@code Example} family whose
     * null-tenantId probe would match every tenant — with a context bound
     * or without one (issue #10 adversarial probe, fixed contract).
     */
    @Test
    @SuppressWarnings("deprecation")
    void operationsThatCannotBeMadeTenantSafeRefuseLoudly() {
        List<String> notRefused = new ArrayList<>();
        TenantMemberEntity probe = new TenantMemberEntity(TENANT_A, USER_A1, TenantRole.TENANT_ADMIN);

        refuse(notRefused, "findAll()", () -> members.findAll());
        refuse(notRefused, "findAll(Sort)", () -> members.findAll(Sort.by("createdAt")));
        refuse(notRefused, "findAll(Pageable)", () -> members.findAll(offsetZero()));
        refuse(notRefused, "getOne", () -> members.getOne(membershipA1));
        refuse(notRefused, "getById", () -> members.getById(membershipA1));
        refuse(notRefused, "getReferenceById", () -> members.getReferenceById(membershipA1));
        refuse(notRefused, "deleteAllById", () -> members.deleteAllById(List.of(membershipA1)));
        refuse(notRefused, "deleteAll(entities)", () -> members.deleteAll(List.of(probe)));
        refuse(notRefused, "deleteAll()", () -> members.deleteAll());
        refuse(notRefused, "deleteAllInBatch()", () -> members.deleteAllInBatch());
        refuse(notRefused, "deleteAllInBatch(entities)", () -> members.deleteAllInBatch(List.of(probe)));
        refuse(notRefused, "deleteAllByIdInBatch", () -> members.deleteAllByIdInBatch(List.of(membershipA1)));
        refuse(notRefused, "deleteInBatch", () -> members.deleteInBatch(List.of(probe)));
        refuse(notRefused, "findOne(Example)", () -> members.findOne(Example.of(probe)));
        refuse(notRefused, "findAll(Example)", () -> members.findAll(Example.of(probe)));
        refuse(notRefused, "findAll(Example, Sort)", () -> members.findAll(Example.of(probe), Sort.by("createdAt")));
        refuse(notRefused, "findAll(Example, Pageable)", () -> members.findAll(Example.of(probe), offsetZero()));
        refuse(notRefused, "count(Example)", () -> members.count(Example.of(probe)));
        refuse(notRefused, "exists(Example)", () -> members.exists(Example.of(probe)));
        refuse(notRefused, "findBy(Example, Function)", () -> members.findBy(Example.of(probe), null));

        TenantContext.end();
        TenantContext.begin(TENANT_B, USER_B1, false);
        refuse(notRefused, "findAll() under a context", () -> members.findAll());
        refuse(notRefused, "deleteAllInBatch() under a context", () -> members.deleteAllInBatch());

        assertThat(notRefused)
                .as("every operation without a tenant-safe meaning must refuse (ADR-0002 inherited-surface policy)")
                .isEmpty();
        Integer survivors = jdbc.queryForObject("SELECT count(*) FROM tenant_members", Integer.class);
        assertThat(survivors).as("refused operations must not mutate anything").isEqualTo(3);
    }

    /**
     * The flush-path write shapes carry the same guard: saveAndFlush of a
     * foreign-tenant entity is refused and persists nothing, and a batch
     * mixing a context-tenant row with a foreign row is refused whole —
     * the seam checks every element before delegating, so there is no
     * partial write of the valid half.
     */
    @Test
    void flushPathAndBatchWritesAreGuardedAtomically() {
        TenantContext.begin(TENANT_A, USER_A1, false);
        assertThatThrownBy(
                        () -> members.saveAndFlush(new TenantMemberEntity(TENANT_B, USER_A1, TenantRole.TENANT_TESTER)))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("write refused");
        assertThat(membershipCount(TENANT_B, USER_A1)).isZero();

        members.saveAndFlush(new TenantMemberEntity(TENANT_A, USER_B1, TenantRole.TENANT_TESTER));
        assertThat(membershipCount(TENANT_A, USER_B1))
                .as("a guarded flush-path save of a context-tenant row persists")
                .isEqualTo(1);

        assertThatThrownBy(() -> members.saveAll(List.of(
                        new TenantMemberEntity(TENANT_A, USER_B1, TenantRole.TENANT_ADMIN),
                        new TenantMemberEntity(TENANT_B, USER_A2, TenantRole.TENANT_TESTER))))
                .as("a mixed batch is refused whole, not half-applied")
                .isInstanceOf(TenantIsolationException.class);
        assertThat(membershipCount(TENANT_B, USER_A2))
                .as("the foreign half of a refused batch never landed")
                .isZero();
    }

    /**
     * The platform-administrator override authorizes administrative
     * cross-tenant deletes (the delete analog of the override write
     * path), while reads keep the route's scope.
     */
    @Test
    void platformOverrideAuthorizesAdministrativeCrossTenantDeletes() {
        TenantContext.begin(TENANT_A, USER_A1, true);
        TenantMemberEntity foreignRow = members.findScoped(membershipA2).orElseThrow();
        TenantContext.end();
        TenantContext.begin(TENANT_B, USER_B1, true);
        members.delete(foreignRow);
        assertThat(membershipCount(TENANT_A, USER_A2))
                .as("the audited override admits administrative deletes across tenants")
                .isZero();
        assertThat(members.findById(membershipA1))
                .as("reads stay context-scoped even under the override")
                .isEmpty();
    }

    /** Membership rows of one tenant-user pair, for persistence assertions. */
    private int membershipCount(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                Integer.class,
                tenantId,
                userId);
        return count == null ? 0 : count;
    }

    /** Records the operation unless the seam refused it. */
    private static void refuse(List<String> notRefused, String operation, Runnable call) {
        try {
            call.run();
            notRefused.add(operation);
        } catch (TenantIsolationException expected) {
            // the seam refused; the secure outcome
        }
    }

    private static org.springframework.data.domain.Pageable offsetZero() {
        return new com.shipperdog.api.config.CursorPageable(0, 10);
    }

    private static Specification<TenantMemberEntity> userEquals(UUID userId) {
        return (root, query, builder) -> builder.equal(root.get("userId"), userId);
    }
}
