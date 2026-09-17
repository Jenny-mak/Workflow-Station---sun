package com.portal.enums;

/**
 * Standing-rule delegate target. USER stores {@code delegate_id}; BU_ROLE stores
 * paired business-unit and role codes. Null on legacy rows is treated as USER.
 */
public enum DelegateTargetType {
    USER,
    BU_ROLE
}
