package com.zcloud.platform.config;

// Anti-pattern: GOD CONSTANTS FILE - every domain's constants crammed into one file
public class Constants {

    // === Auth Constants ===
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_AGENT = "AGENT";
    public static final String ROLE_BROKER = "BROKER";
    public static final String ROLE_LOAN_OFFICER = "LOAN_OFFICER";
    public static final String ROLE_UNDERWRITER = "UNDERWRITER";
    public static final String ROLE_PROCESSOR = "PROCESSOR";
    public static final String ROLE_APPRAISER = "APPRAISER";
    public static final String ROLE_CLOSING_AGENT = "CLOSING_AGENT";
    public static final String ROLE_CLIENT = "CLIENT";
    public static final String DEFAULT_PASSWORD = "Welcome123!"; // anti-pattern: default password
    public static final String JWT_SECRET = "homelend-super-secret-key-do-not-share"; // anti-pattern: hardcoded
    public static final long JWT_EXPIRATION_MS = 86400000;

    // === Listing Constants ===
    public static final String LISTING_ACTIVE = "ACTIVE";
    public static final String LISTING_PENDING = "PENDING";
    public static final String LISTING_SOLD = "SOLD";
    public static final String LISTING_WITHDRAWN = "WITHDRAWN";
    public static final String LISTING_EXPIRED = "EXPIRED";
    public static final String LISTING_COMING_SOON = "COMING_SOON";

    // === Property Type Constants ===
    public static final String PROP_SINGLE_FAMILY = "SINGLE_FAMILY";
    public static final String PROP_CONDO = "CONDO";
    public static final String PROP_TOWNHOUSE = "TOWNHOUSE";
    public static final String PROP_MULTI_FAMILY = "MULTI_FAMILY";
    public static final String PROP_COMMERCIAL = "COMMERCIAL";
    public static final String PROP_LAND = "LAND";

    // === Loan Constants ===
    public static final String LOAN_STATUS_STARTED = "STARTED";
    public static final String LOAN_STATUS_SUBMITTED = "SUBMITTED";
    public static final String LOAN_STATUS_PROCESSING = "PROCESSING";
    public static final String LOAN_STATUS_UNDERWRITING = "UNDERWRITING";
    public static final String LOAN_STATUS_APPROVED = "APPROVED";
    public static final String LOAN_STATUS_CONDITIONALLY_APPROVED = "CONDITIONALLY_APPROVED";
    public static final String LOAN_STATUS_DENIED = "DENIED";
    public static final String LOAN_STATUS_SUSPENDED = "SUSPENDED";
    public static final String LOAN_STATUS_CLOSING = "CLOSING";
    public static final String LOAN_STATUS_FUNDED = "FUNDED";
    public static final String LOAN_STATUS_SERVICING = "SERVICING";

    // === Loan Type Constants ===
    public static final String LOAN_TYPE_CONVENTIONAL = "CONVENTIONAL";
    public static final String LOAN_TYPE_FHA = "FHA";
    public static final String LOAN_TYPE_VA = "VA";
    public static final String LOAN_TYPE_USDA = "USDA";
    public static final String LOAN_TYPE_JUMBO = "JUMBO";
    public static final String LOAN_TYPE_ARM = "ARM";
    public static final String LOAN_TYPE_FIXED = "FIXED";

    // === Offer Constants ===
    public static final String OFFER_PENDING = "PENDING";
    public static final String OFFER_ACCEPTED = "ACCEPTED";
    public static final String OFFER_REJECTED = "REJECTED";
    public static final String OFFER_COUNTERED = "COUNTERED";
    public static final String OFFER_EXPIRED = "EXPIRED";
    public static final String OFFER_WITHDRAWN = "WITHDRAWN";

    // === Underwriting Constants ===
    public static final String UW_APPROVED = "APPROVED";
    public static final String UW_DENIED = "DENIED";
    public static final String UW_SUSPENDED = "SUSPENDED";
    public static final String UW_CONDITIONAL = "CONDITIONAL";
    public static final int MIN_CREDIT_SCORE_CONVENTIONAL = 620;
    public static final int MIN_CREDIT_SCORE_FHA = 580;
    public static final double MAX_DTI_RATIO = 0.43;
    public static final double MAX_LTV_CONVENTIONAL = 0.97;
    public static final double MAX_LTV_JUMBO = 0.80;

    // === Commission Constants ===
    public static final double DEFAULT_COMMISSION_RATE = 0.06; // anti-pattern: hardcoded business rule
    public static final double LISTING_AGENT_SPLIT = 0.50;
    public static final double BUYER_AGENT_SPLIT = 0.50;

    // === Closing Constants ===
    public static final String CLOSING_SCHEDULED = "SCHEDULED";
    public static final String CLOSING_IN_PROGRESS = "IN_PROGRESS";
    public static final String CLOSING_COMPLETED = "COMPLETED";
    public static final String CLOSING_CANCELLED = "CANCELLED";

    // === Payment Constants ===
    public static final String PAYMENT_ON_TIME = "ON_TIME";
    public static final String PAYMENT_LATE = "LATE";
    public static final String PAYMENT_MISSED = "MISSED";
    public static final String PAYMENT_PARTIAL = "PARTIAL";
    public static final int LATE_FEE_GRACE_DAYS = 15;
    public static final double LATE_FEE_PERCENTAGE = 0.05; // anti-pattern: hardcoded

    // === Misc ===
    public static final int MAX_RESULTS = 1000;
    public static final int CACHE_TTL_SECONDS = 300;
    public static final String EMAIL_FROM = "noreply@homelend-pro.com";
    public static final String SYSTEM_USER = "SYSTEM";
    public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final double ESCROW_RESERVE_MONTHS = 2.0; // anti-pattern: business rule as constant
}
