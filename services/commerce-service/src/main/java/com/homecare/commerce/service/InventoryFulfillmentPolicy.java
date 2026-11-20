package com.homecare.commerce.service;

public final class InventoryFulfillmentPolicy {

    private InventoryFulfillmentPolicy() {
    }

    public static boolean shouldDecrement(String partSource) {
        return !"CUSTOMER_SUPPLIED".equalsIgnoreCase(partSource);
    }
}
