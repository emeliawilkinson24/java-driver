/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.mapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.mapper.MapperException;
import com.datastax.oss.driver.api.mapper.annotations.Dao;
import com.datastax.oss.driver.api.mapper.annotations.DaoFactory;
import com.datastax.oss.driver.api.mapper.annotations.DaoKeyspace;
import com.datastax.oss.driver.api.mapper.annotations.DefaultNullSavingStrategy;
import com.datastax.oss.driver.api.mapper.annotations.Entity;
import com.datastax.oss.driver.api.mapper.annotations.Mapper;
import com.datastax.oss.driver.api.mapper.annotations.PartitionKey;
import com.datastax.oss.driver.api.mapper.annotations.Select;
import com.datastax.oss.driver.api.mapper.annotations.Update;
import com.datastax.oss.driver.api.mapper.entity.saving.NullSavingStrategy;
import com.datastax.oss.driver.api.testinfra.ccm.CcmRule;
import com.datastax.oss.driver.api.testinfra.session.SessionRule;
import com.datastax.oss.driver.api.testinfra.session.SessionUtils;
import com.datastax.oss.driver.categories.ParallelizableTests;
import java.util.Objects;
import java.util.UUID;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

@Category(ParallelizableTests.class)
public class NullSavingStrategyIT {

  private static final CcmRule CCM_RULE = CcmRule.getInstance();

  private static final SessionRule<CqlSession> SESSION_RULE = SessionRule.builder(CCM_RULE).build();

  // JAVA-3076: V3 protocol calls that could trigger cassandra to issue client warnings appear to be
  // inherently unstable when used at the same time as V4+ protocol clients (common since this is
  // part of the parallelizable test suite).
  //
  // For this test we'll use latest protocol version for SessionRule set-up, which creates the
  // keyspace and could potentially result in warning about too many keyspaces, and then create a
  // new client for the tests to use, which they access via the static InventoryMapper instance
  // `mapper`.
  //
  // This additional client is created in the @BeforeClass method #setup() and guaranteed to be
  // closed in @AfterClass method #teardown().
  //
  // Note: The standard junit runner executes rules before class/test setup so the order of
  // execution will be CcmRule#before > SessionRule#before > NullSavingStrategyIT#setup, meaning
  // CCM_RULE/SESSION_RULE should be fully initialized by the time #setup() is invoked.
  private static CqlSession v3Session;

  @ClassRule
  public static final TestRule CHAIN = RuleChain.outerRule(CCM_RULE).around(SESSION_RULE);

  private static InventoryMapper mapper;

  @BeforeClass
  public static void setup() {
    // setup table for use in tests, this can use the default session
    SESSION_RULE
        .session()
        .execute(
            SimpleStatement.builder(
                    "CREATE TABLE product_simple(id uuid PRIMARY KEY, description text)")
                .setExecutionProfile(SESSION_RULE.slowProfile())
                .build());

    // Create V3 protocol session for use in tests, will be closed in #teardown()
    v3Session =
        SessionUtils.newSession(
            CCM_RULE,
            SESSION_RULE.keyspace(),
            DriverConfigLoader.programmaticBuilder()
                .withString(DefaultDriverOption.PROTOCOL_VERSION, "V3")
                .build());

    // Hand V3 session to InventoryMapper which the tests will use to perform db calls
    mapper = new NullSavingStrategyIT_InventoryMapperBuilder(v3Session).build();
  }

  @AfterClass
  public static void teardown() {
    // Close V3 session (SESSION_RULE will be closed separately by @ClassRule handling)
    if (v3Session != null) {
      v3Session.close();
    }
  }

  @Test
  public void should_throw_when_try_to_construct_dao_with_DO_NOT_SET_strategy_for_V3_protocol() {
    assertThatThrownBy(() -> mapper.productDao(SESSION_RULE.keyspace()))
        .isInstanceOf(MapperException.class)
        .hasMessage("You cannot use NullSavingStrategy.DO_NOT_SET for protocol version V3.");
  }

  @Test
  public void
      should_throw_when_try_to_construct_dao_with_DO_NOT_SET_implicit_strategy_for_V3_protocol() {
    assertThatThrownBy(() -> mapper.productDaoImplicit(SESSION_RULE.keyspace()))
        .isInstanceOf(MapperException.class)
        .hasMessage("You cannot use NullSavingStrategy.DO_NOT_SET for protocol version V3.");
  }

  @Test
  public void
      should_throw_when_try_to_construct_dao_with_DO_NOT_SET_strategy_set_globally_for_V3_protocol() {
    assertThatThrownBy(() -> mapper.productDaoDefault(SESSION_RULE.keyspace()))
        .isInstanceOf(MapperException.class)
        .hasMessage("You cannot use NullSavingStrategy.DO_NOT_SET for protocol version V3.");
  }

  @Test
  public void should_do_not_throw_when_construct_dao_with_global_level_SET_TO_NULL() {
    assertThatCode(() -> mapper.productDaoGlobalLevelSetToNull(SESSION_RULE.keyspace()))
        .doesNotThrowAnyException();
  }

  @Test
  public void should_do_not_throw_when_construct_dao_with_parent_interface_SET_TO_NULL() {
    assertThatCode(() -> mapper.productDaoSetToNullFromParentInterface(SESSION_RULE.keyspace()))
        .doesNotThrowAnyException();
  }

  @Test
  public void
      should_do_not_throw_when_construct_dao_with_global_level_DO_NOT_SET_and_local_override_to_SET_TO_NULL() {
    assertThatCode(() -> mapper.productDaoLocalOverride(SESSION_RULE.keyspace()))
        .doesNotThrowAnyException();
  }

  @Mapper
  public interface InventoryMapper {
    @DaoFactory
    ProductSimpleDao productDao(@DaoKeyspace CqlIdentifier keyspace);

    @DaoFactory
    ProductSimpleDaoDefault productDaoDefault(@DaoKeyspace CqlIdentifier keyspace);

    @DaoFactory
    ProductSimpleDaoImplicit productDaoImplicit(@DaoKeyspace CqlIdentifier keyspace);

    @DaoFactory
    ProductSimpleDaoGlobalLevelSetToNull productDaoGlobalLevelSetToNull(
        @DaoKeyspace CqlIdentifier keyspace);

    @DaoFactory
    ProductSimpleDaoSetToNullFromParentInterface productDaoSetToNullFromParentInterface(
        @DaoKeyspace CqlIdentifier keyspace);

    @DaoFactory
    ProductSimpleDaoGlobalLevelDoNotSetOverrideSetToNull productDaoLocalOverride(
        @DaoKeyspace CqlIdentifier keyspace);
  }

  @DefaultNullSavingStrategy(NullSavingStrategy.SET_TO_NULL)
  public interface SetToNull {}

  @Dao
  public interface ProductSimpleDao {

    @Update(nullSavingStrategy = NullSavingStrategy.DO_NOT_SET)
    void update(ProductSimple product);

    @Select
    ProductSimple findById(UUID productId);
  }

  @Dao
  public interface ProductSimpleDaoImplicit {

    @Update
    void update(ProductSimple product);

    @Select
    ProductSimple findById(UUID productId);
  }

  @Dao
  @DefaultNullSavingStrategy(NullSavingStrategy.DO_NOT_SET)
  public interface ProductSimpleDaoDefault extends SetToNull {
    @Update
    void update(ProductSimple product);

    @Select
    ProductSimple findById(UUID productId);
  }

  @Dao
  @DefaultNullSavingStrategy(NullSavingStrategy.SET_TO_NULL)
  public interface ProductSimpleDaoGlobalLevelSetToNull {

    @Update
    void update(ProductSimple product);

    @Select
    ProductSimple findById(UUID productId);
  }

  @Dao
  public interface ProductSimpleDaoSetToNullFromParentInterface
      extends ProductSimpleDaoImplicit, SetToNull {}

  @Dao
  @DefaultNullSavingStrategy(NullSavingStrategy.DO_NOT_SET)
  public interface ProductSimpleDaoGlobalLevelDoNotSetOverrideSetToNull {

    @Update(nullSavingStrategy = NullSavingStrategy.SET_TO_NULL)
    void update(ProductSimple product);

    @Select
    ProductSimple findById(UUID productId);
  }

  @Entity
  public static class ProductSimple {
    @PartitionKey private UUID id;
    private String description;

    public ProductSimple() {}

    public ProductSimple(UUID id, String description) {
      this.id = id;
      this.description = description;
    }

    public UUID getId() {
      return id;
    }

    public void setId(UUID id) {
      this.id = id;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    @Override
    public boolean equals(Object other) {

      if (this == other) {
        return true;
      } else if (other instanceof ProductSimple) {
        ProductSimple that = (ProductSimple) other;
        return Objects.equals(this.id, that.id)
            && Objects.equals(this.description, that.description);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, description);
    }

    @Override
    public String toString() {
      return "ProductSimple{" + "id=" + id + ", description='" + description + '\'' + '}';
    }
  }
}
