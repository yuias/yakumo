package app.rly3h.yakumo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterRegistryTest {
  @Test
  fun registerReturnsPreviousRouter() {
    val registry = RouterRegistry<String>()
    assertNull(registry.register("a"))
    assertEquals("a", registry.register("b"))
  }

  @Test
  fun clearIgnoresAReplacedRouter() {
    val registry = RouterRegistry<String>()
    registry.register("a")
    registry.register("b")
    registry.clear("a")
    assertEquals("b", registry.takeIfGeneration(registry.generation()))
  }

  @Test
  fun takeReleasesTheRouterOfTheOwnerGeneration() {
    val registry = RouterRegistry<String>()
    registry.register("a")
    val owner = registry.generation()
    assertEquals("a", registry.takeIfGeneration(owner))
    assertNull(registry.takeIfGeneration(owner))
  }

  @Test
  fun staleOwnerCannotTakeANewerRouter() {
    val registry = RouterRegistry<String>()
    registry.register("a")
    val staleOwner = registry.generation()
    registry.register("b")
    assertNull(registry.takeIfGeneration(staleOwner))
    assertEquals("b", registry.takeIfGeneration(registry.generation()))
  }

  @Test
  fun clearedRouterIsNotTakenAgain() {
    val registry = RouterRegistry<String>()
    registry.register("a")
    val owner = registry.generation()
    registry.clear("a")
    assertNull(registry.takeIfGeneration(owner))
  }
}
