package dev.chainbank.api.chain;

import io.quarkus.test.common.QuarkusTestResource;

/** Registers the Anvil chain for every @QuarkusTest in this module (not restricted to one class). */
@QuarkusTestResource(value = AnvilResource.class, restrictToAnnotatedClass = false)
public class ChainTestResources {
}
