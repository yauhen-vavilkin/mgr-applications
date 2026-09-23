package org.folio.am.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.folio.am.domain.entity.ModuleBootstrapView;
import org.folio.am.domain.entity.ModuleEntity;
import org.folio.am.support.base.BaseRepositoryTest;
import org.folio.test.types.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Sql(scripts =
  {
    "classpath:/sql/application-descriptor-with-routes.sql",
    "classpath:/sql/module-interface-references.sql"
  }, executionPhase = BEFORE_TEST_METHOD)
@Sql(
  scripts = "classpath:/sql/truncate-tables.sql",
  executionPhase = AFTER_TEST_METHOD
)
class ModuleBootstrapRepositoryIT extends BaseRepositoryTest {

  private static final String APP_1_0_0_ID = "test-app-1.0.0";
  private static final String APP_2_0_0_ID = "test-app-2.0.0";
  private static final String MODULE_FOO_ID = "test-module-foo-1.0.0";
  private static final String MODULE_BAR_ID = "test-module-bar-1.0.0";
  private static final String MODULE_BAZ_ID = "test-module-baz-1.0.0";
  private static final String MODULE_FOO_DISCOVERY_URL = "http://test-module-foo:8080";
  private static final String MODULE_BAR_DISCOVERY_URL = "http://test-module-bar:8080";
  private static final String MODULE_BAZ_DISCOVERY_URL = "http://test-module-baz:8080";

  @Autowired
  private ModuleBootstrapRepository repository;

  @Autowired
  private ModuleRepository moduleRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  void findAllByIdForUpdate_blocksConcurrentRequestAndReturnsCommittedValue() throws Exception {
    var transactionTemplate = new TransactionTemplate(transactionManager);
    var lockAcquired = new CountDownLatch(1);
    var releaseLock = new CountDownLatch(1);
    var queryStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    try {
      final Future<Void> firstTransaction = executor.submit(() -> transactionTemplate.execute(status -> {
        var module = moduleRepository.findAllByIdForUpdate(List.of(MODULE_BAZ_ID)).getFirst();
        lockAcquired.countDown();
        module.setDiscoveryUrl(MODULE_BAZ_DISCOVERY_URL + "-updated");
        moduleRepository.saveAndFlush(module);
        await(releaseLock);
        return null;
      }));

      assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
      Future<List<ModuleEntity>> secondTransaction = executor.submit(() ->
        transactionTemplate.execute(status -> {
          queryStarted.countDown();
          return moduleRepository.findAllByIdForUpdate(List.of(MODULE_BAZ_ID));
        }));
      assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();

      try {
        secondTransaction.get(200, TimeUnit.MILLISECONDS);
        throw new AssertionError("Concurrent request was not blocked by the row lock");
      } catch (TimeoutException expected) {
        // The second request must wait until the first transaction commits.
      } finally {
        releaseLock.countDown();
      }

      firstTransaction.get(5, TimeUnit.SECONDS);
      var result = secondTransaction.get(5, TimeUnit.SECONDS);
      assertThat(result).hasSize(1);
      assertThat(result.getFirst().getDiscoveryUrl()).isEqualTo(MODULE_BAZ_DISCOVERY_URL + "-updated");
    } finally {
      releaseLock.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void shouldReturnAllRequiredModulesWithDiscoveryUrls() {
    var result = repository.findAllRequiredByModuleId(MODULE_FOO_ID);
    assertThat(result)
      .hasSize(3)
      .anyMatch(matchView(MODULE_FOO_ID, APP_1_0_0_ID, MODULE_FOO_DISCOVERY_URL))
      .anyMatch(matchView(MODULE_BAR_ID, APP_2_0_0_ID, MODULE_BAR_DISCOVERY_URL))
      .anyMatch(matchView(MODULE_BAZ_ID, APP_2_0_0_ID, MODULE_BAZ_DISCOVERY_URL));

    moduleRepository.findById(MODULE_BAZ_ID).stream().peek(moduleEntity -> moduleEntity.setDiscoveryUrl(null))
      .forEach(moduleRepository::save);
    moduleRepository.flush();

    result = repository.findAllRequiredByModuleId(MODULE_FOO_ID);
    assertThat(result)
      .hasSize(2)
      .anyMatch(matchView(MODULE_FOO_ID, APP_1_0_0_ID, MODULE_FOO_DISCOVERY_URL))
      .anyMatch(matchView(MODULE_BAR_ID, APP_2_0_0_ID, MODULE_BAR_DISCOVERY_URL));
  }

  @Test
  void shouldReturnOnlyModuleIfNoDependencies() {
    var result = repository.findAllRequiredByModuleId(MODULE_BAR_ID);
    assertThat(result)
      .hasSize(1)
      .anyMatch(matchView(MODULE_BAR_ID, APP_2_0_0_ID, MODULE_BAR_DISCOVERY_URL));
  }

  @Test
  void findAllRequiredByModuleIdAndApplicationIdsIn_returnsOnlyInScopeModules() {
    var result = repository.findAllRequiredByModuleIdAndApplicationIdsIn(MODULE_FOO_ID, List.of(APP_1_0_0_ID));
    assertThat(result)
      .hasSize(1)
      .anyMatch(matchView(MODULE_FOO_ID, APP_1_0_0_ID, MODULE_FOO_DISCOVERY_URL));
  }

  @Test
  void findAllRequiredByModuleIdAndApplicationIdsIn_returnsAllWhenAllAppsInScope() {
    var result = repository.findAllRequiredByModuleIdAndApplicationIdsIn(
      MODULE_FOO_ID, List.of(APP_1_0_0_ID, APP_2_0_0_ID));
    assertThat(result)
      .hasSize(3)
      .anyMatch(matchView(MODULE_FOO_ID, APP_1_0_0_ID, MODULE_FOO_DISCOVERY_URL))
      .anyMatch(matchView(MODULE_BAR_ID, APP_2_0_0_ID, MODULE_BAR_DISCOVERY_URL))
      .anyMatch(matchView(MODULE_BAZ_ID, APP_2_0_0_ID, MODULE_BAZ_DISCOVERY_URL));
  }

  @Test
  void findAllRequiredByModuleIdAndApplicationIdsIn_excludesSelfWhenSelfNotInScope() {
    var result = repository.findAllRequiredByModuleIdAndApplicationIdsIn(MODULE_FOO_ID, List.of(APP_2_0_0_ID));
    assertThat(result)
      .hasSize(2)
      .noneMatch(view -> view.getId().equals(MODULE_FOO_ID))
      .anyMatch(matchView(MODULE_BAR_ID, APP_2_0_0_ID, MODULE_BAR_DISCOVERY_URL))
      .anyMatch(matchView(MODULE_BAZ_ID, APP_2_0_0_ID, MODULE_BAZ_DISCOVERY_URL));
  }

  @Test
  void findViewsById_returnsSelfRow() {
    var result = repository.findViewsById(MODULE_FOO_ID);
    assertThat(result)
      .hasSize(1)
      .anyMatch(matchView(MODULE_FOO_ID, APP_1_0_0_ID, MODULE_FOO_DISCOVERY_URL));
  }

  @Test
  void findAllRequiredByModuleId_returnsProviderOnceWhenItProvidesMultipleRequiredInterfaces() {
    // test-module-bar provides both test-bar-interface and test-bar-interface-2, and test-module-foo requires both;
    // the provider must appear once (the IN subquery is a semi-join), not be fanned out now that DISTINCT is gone.
    var result = repository.findAllRequiredByModuleId(MODULE_FOO_ID);

    assertThat(result).hasSize(3);
    assertThat(result).filteredOn(view -> view.getId().equals(MODULE_BAR_ID)).hasSize(1);
    assertNoDuplicateRows(result);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void assertNoDuplicateRows(List<ModuleBootstrapView> views) {
    assertThat(views)
      .extracting(view -> view.getId() + "@" + view.getApplicationId())
      .doesNotHaveDuplicates();
  }

  private Predicate<ModuleBootstrapView> matchView(String moduleId, String appId, String location) {
    return view ->
      view.getId().equals(moduleId)
        && view.getApplicationId().equals(appId)
        && view.getLocation().equals(location)
        && view.getDescriptor() != null;
  }
}
