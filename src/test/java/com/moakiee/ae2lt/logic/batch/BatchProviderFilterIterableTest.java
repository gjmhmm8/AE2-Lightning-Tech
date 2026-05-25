package com.moakiee.ae2lt.logic.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingProvider;

class BatchProviderFilterIterableTest {

    @Test
    void filtersExcludedProvidersByIdentity() {
        var first = provider();
        var equalButDifferent = first.toString();
        var excluded = providerWithString(equalButDifferent);
        var sameString = providerWithString(equalButDifferent);

        var excludedByIdentity = new IdentityHashMap<ICraftingProvider, Boolean>();
        excludedByIdentity.put(excluded, Boolean.TRUE);

        var filtered = new BatchProviderFilterIterable(
                List.of(first, excluded, sameString),
                excludedByIdentity);

        assertEquals(List.of(first, sameString), iterableToList(filtered));
    }

    @Test
    void iteratorThrowsWhenExhausted() {
        var filtered = new BatchProviderFilterIterable(
                List.<ICraftingProvider>of(),
                new IdentityHashMap<>());

        var iterator = filtered.iterator();

        assertThrows(NoSuchElementException.class, iterator::next);
    }

    private static List<ICraftingProvider> iterableToList(Iterable<ICraftingProvider> iterable) {
        var result = new java.util.ArrayList<ICraftingProvider>();
        for (var provider : iterable) {
            result.add(provider);
        }
        return result;
    }

    private static ICraftingProvider provider() {
        return providerWithString("provider");
    }

    private static ICraftingProvider providerWithString(String text) {
        return (ICraftingProvider) Proxy.newProxyInstance(
                ICraftingProvider.class.getClassLoader(),
                new Class<?>[] { ICraftingProvider.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> text;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }
}
