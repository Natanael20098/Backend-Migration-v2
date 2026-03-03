-- ============================================================================
-- HomeLend Pro Platform - Initial Database Schema
-- "One schema to rule them all"
--
-- This is the monolithic schema for the HomeLend Pro real estate + mortgage
-- platform. All domains (property listings, agent management, loan origination,
-- underwriting, appraisal, closing/settlement, loan servicing, and admin) live
-- together in a single schema with no separation of concerns.
--
-- NOTE: Requires PostgreSQL 13+ for gen_random_uuid() support.
-- ============================================================================

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================================
-- BROKERAGE / AGENT TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS brokerages (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(100),
    state           VARCHAR(50),
    zip_code        VARCHAR(20),
    phone           VARCHAR(30),
    email           VARCHAR(255),
    license_number  VARCHAR(100),
    website         VARCHAR(500),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agents (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(30),
    license_number  VARCHAR(100),
    brokerage_id    UUID REFERENCES brokerages(id),
    hire_date       DATE,
    is_active       BOOLEAN DEFAULT TRUE,
    commission_rate NUMERIC(5, 4),
    bio             TEXT,
    photo_url       VARCHAR(500),
    -- Denormalized brokerage info (anti-pattern: duplicated data)
    brokerage_name  VARCHAR(255),
    brokerage_phone VARCHAR(30),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_licenses (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    agent_id        UUID REFERENCES agents(id),
    license_type    VARCHAR(100),
    license_number  VARCHAR(100),
    state           VARCHAR(50),
    issue_date      DATE,
    expiry_date     DATE,
    status          VARCHAR(50) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS commissions (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    agent_id        UUID REFERENCES agents(id),
    listing_id      UUID,  -- FK added after listings table is created
    transaction_id  VARCHAR(100),
    amount          NUMERIC(12, 2),
    commission_rate NUMERIC(5, 4),
    type            VARCHAR(50),  -- 'LISTING' or 'BUYER' -- no CHECK constraint (anti-pattern)
    status          VARCHAR(50) DEFAULT 'PENDING',
    paid_date       DATE,
    -- Denormalized agent info (anti-pattern)
    agent_name      VARCHAR(255),
    agent_email     VARCHAR(255),
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- PROPERTY / LISTING TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS properties (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(100),
    city            VARCHAR(100),
    state           VARCHAR(50),
    zip_code        VARCHAR(20),
    county          VARCHAR(100),
    latitude        NUMERIC(10, 7),
    longitude       NUMERIC(10, 7),
    beds            INTEGER,
    baths           NUMERIC(3, 1),
    sqft            INTEGER,
    lot_size        NUMERIC(10, 2),
    year_built      INTEGER,
    property_type   VARCHAR(50),  -- 'SINGLE_FAMILY','CONDO','TOWNHOUSE','MULTI_FAMILY','LAND' -- no CHECK
    description     TEXT,
    parking_spaces  INTEGER,
    garage_type     VARCHAR(50),
    hoa_fee         NUMERIC(10, 2),
    zoning          VARCHAR(50),
    parcel_number   VARCHAR(100),
    -- Denormalized "last known" values (anti-pattern: stale data)
    last_sold_price NUMERIC(12, 2),
    last_sold_date  DATE,
    current_tax_amount NUMERIC(10, 2),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS property_images (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    property_id     UUID REFERENCES properties(id),
    url             VARCHAR(500),       -- plain text file path (anti-pattern)
    caption         VARCHAR(255),
    is_primary      BOOLEAN DEFAULT FALSE,
    display_order   INTEGER DEFAULT 0,
    file_size_bytes BIGINT,
    content_type    VARCHAR(100),
    uploaded_at     TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS listings (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    property_id     UUID REFERENCES properties(id),
    agent_id        UUID REFERENCES agents(id),
    list_price      NUMERIC(12, 2),
    original_price  NUMERIC(12, 2),
    status          VARCHAR(50) DEFAULT 'ACTIVE',  -- 'ACTIVE','PENDING','SOLD','WITHDRAWN','EXPIRED' -- no CHECK
    mls_number      VARCHAR(50),
    listed_date     DATE,
    expiry_date     DATE,
    sold_date       DATE,
    sold_price      NUMERIC(12, 2),
    days_on_market  INTEGER,
    -- Denormalized property info (anti-pattern: duplicated from properties)
    property_address VARCHAR(500),
    property_city   VARCHAR(100),
    property_state  VARCHAR(50),
    property_zip    VARCHAR(20),
    property_beds   INTEGER,
    property_baths  NUMERIC(3, 1),
    property_sqft   INTEGER,
    description     TEXT,
    virtual_tour_url VARCHAR(500),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Now add the FK on commissions that references listings
ALTER TABLE commissions
    DROP CONSTRAINT IF EXISTS fk_commissions_listing;
ALTER TABLE commissions
    ADD CONSTRAINT fk_commissions_listing FOREIGN KEY (listing_id) REFERENCES listings(id);

CREATE TABLE IF NOT EXISTS open_houses (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    listing_id      UUID REFERENCES listings(id),
    date            DATE,
    start_time      TIME,
    end_time        TIME,
    agent_id        UUID REFERENCES agents(id),
    notes           TEXT,
    attendee_count  INTEGER,
    -- Denormalized listing address (anti-pattern)
    property_address VARCHAR(500),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS property_tax_records (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    property_id     UUID REFERENCES properties(id),
    year            INTEGER,
    assessed_value  NUMERIC(12, 2),
    tax_amount      NUMERIC(10, 2),
    tax_rate        NUMERIC(6, 4),
    exemptions      TEXT,  -- JSON stored as TEXT (anti-pattern)
    paid            BOOLEAN DEFAULT FALSE,
    paid_date       DATE,
    -- Denormalized property address (anti-pattern)
    property_address VARCHAR(500),
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- CLIENT / LEAD TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS clients (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(30),
    ssn_encrypted   VARCHAR(500),  -- Storing SSN even encrypted in main table (anti-pattern)
    date_of_birth   DATE,
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(100),
    city            VARCHAR(100),
    state           VARCHAR(50),
    zip_code        VARCHAR(20),
    client_type     VARCHAR(50) DEFAULT 'BUYER',  -- 'BUYER','SELLER','BOTH','BORROWER' -- no CHECK
    assigned_agent_id UUID REFERENCES agents(id),
    -- Denormalized agent info (anti-pattern)
    agent_name      VARCHAR(255),
    agent_email     VARCHAR(255),
    agent_phone     VARCHAR(30),
    preferred_contact_method VARCHAR(50),
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS leads (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    client_id       UUID REFERENCES clients(id),
    source          VARCHAR(100),  -- 'WEBSITE','REFERRAL','ZILLOW','REALTOR_COM','WALK_IN' -- no CHECK
    status          VARCHAR(50) DEFAULT 'NEW',  -- 'NEW','CONTACTED','QUALIFIED','LOST' -- no CHECK
    notes           TEXT,
    assigned_agent_id UUID REFERENCES agents(id),
    -- Denormalized client info (anti-pattern)
    client_name     VARCHAR(255),
    client_email    VARCHAR(255),
    client_phone    VARCHAR(30),
    -- Denormalized agent info (anti-pattern)
    agent_name      VARCHAR(255),
    property_interest TEXT,  -- JSON blob as TEXT (anti-pattern)
    budget_min      NUMERIC(12, 2),
    budget_max      NUMERIC(12, 2),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS client_documents (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    client_id       UUID REFERENCES clients(id),
    document_type   VARCHAR(100),
    file_name       VARCHAR(500),
    file_path       VARCHAR(500),  -- plain file system path (anti-pattern)
    file_size_bytes BIGINT,
    mime_type       VARCHAR(100),
    uploaded_at     TIMESTAMP DEFAULT NOW(),
    verified        BOOLEAN DEFAULT FALSE,
    verified_by     UUID REFERENCES agents(id),
    verified_at     TIMESTAMP,
    notes           TEXT
);

-- ============================================================================
-- SHOWING / OFFER TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS showings (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    listing_id      UUID REFERENCES listings(id),
    client_id       UUID REFERENCES clients(id),
    agent_id        UUID REFERENCES agents(id),
    scheduled_date  TIMESTAMP,
    duration_minutes INTEGER DEFAULT 30,
    status          VARCHAR(50) DEFAULT 'SCHEDULED',  -- 'SCHEDULED','COMPLETED','CANCELLED','NO_SHOW' -- no CHECK
    feedback        TEXT,
    rating          INTEGER,  -- 1-5 but no CHECK constraint (anti-pattern)
    -- Denormalized listing info (anti-pattern)
    property_address VARCHAR(500),
    list_price      NUMERIC(12, 2),
    -- Denormalized client info (anti-pattern)
    client_name     VARCHAR(255),
    client_phone    VARCHAR(30),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS offers (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    listing_id      UUID REFERENCES listings(id),
    buyer_client_id UUID REFERENCES clients(id),
    buyer_agent_id  UUID REFERENCES agents(id),
    offer_amount    NUMERIC(12, 2),
    earnest_money   NUMERIC(10, 2),
    contingencies   TEXT,  -- JSON stored as TEXT (anti-pattern)
    financing_type  VARCHAR(100),
    closing_date_requested DATE,
    status          VARCHAR(50) DEFAULT 'SUBMITTED',  -- 'SUBMITTED','ACCEPTED','REJECTED','COUNTERED','WITHDRAWN','EXPIRED'
    expiry_date     TIMESTAMP,
    submitted_at    TIMESTAMP DEFAULT NOW(),
    responded_at    TIMESTAMP,
    -- Denormalized listing info (anti-pattern)
    property_address VARCHAR(500),
    list_price      NUMERIC(12, 2),
    -- Denormalized buyer info (anti-pattern)
    buyer_name      VARCHAR(255),
    buyer_phone     VARCHAR(30),
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS counteroffers (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    offer_id        UUID REFERENCES offers(id),
    counter_amount  NUMERIC(12, 2),
    terms           TEXT,  -- JSON stored as TEXT (anti-pattern)
    status          VARCHAR(50) DEFAULT 'PENDING',
    created_by      UUID,  -- intentionally not FK-constrained (anti-pattern: could be agent or client)
    responded_by    UUID,  -- intentionally not FK-constrained
    response_date   TIMESTAMP,
    expiry_date     TIMESTAMP,
    notes           TEXT,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- LOAN ORIGINATION TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS loan_applications (
    id                      UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    borrower_id             UUID REFERENCES clients(id),  -- client used as borrower (anti-pattern: overloaded table)
    co_borrower_id          UUID REFERENCES clients(id),
    property_id             UUID REFERENCES properties(id),
    loan_type               VARCHAR(100),  -- 'CONVENTIONAL','FHA','VA','USDA','JUMBO' -- no CHECK
    loan_purpose            VARCHAR(100),  -- 'PURCHASE','REFINANCE','CASH_OUT_REFI'
    loan_amount             NUMERIC(12, 2),
    interest_rate           NUMERIC(6, 4),
    loan_term_months        INTEGER,
    down_payment            NUMERIC(12, 2),
    down_payment_percent    NUMERIC(5, 2),
    loan_officer_id         UUID REFERENCES agents(id),  -- agent used as loan officer (anti-pattern: overloaded table)
    processor_id            UUID REFERENCES agents(id),
    status                  VARCHAR(50) DEFAULT 'STARTED',  -- 'STARTED','IN_PROGRESS','SUBMITTED','APPROVED','DENIED','WITHDRAWN','CLOSED'
    application_date        DATE,
    estimated_closing_date  DATE,
    actual_closing_date     DATE,
    -- Denormalized borrower info (anti-pattern)
    borrower_name           VARCHAR(255),
    borrower_email          VARCHAR(255),
    borrower_phone          VARCHAR(30),
    borrower_ssn_encrypted  VARCHAR(500),  -- SSN duplicated here too (anti-pattern)
    -- Denormalized property info (anti-pattern)
    property_address        VARCHAR(500),
    property_value          NUMERIC(12, 2),
    -- Stashed computed values (anti-pattern: can become stale)
    monthly_payment         NUMERIC(10, 2),
    total_interest          NUMERIC(12, 2),
    apr                     NUMERIC(6, 4),
    notes                   TEXT,
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS borrower_employment (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    employer_name       VARCHAR(255),
    position            VARCHAR(255),
    monthly_income      NUMERIC(10, 2),
    start_date          DATE,
    end_date            DATE,
    is_current          BOOLEAN DEFAULT TRUE,
    employer_phone      VARCHAR(30),
    employer_address    VARCHAR(500),
    years_in_field      INTEGER,
    verification_status VARCHAR(50) DEFAULT 'PENDING',
    -- Denormalized borrower info (anti-pattern)
    borrower_name       VARCHAR(255),
    created_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS borrower_assets (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    asset_type          VARCHAR(100),  -- 'CHECKING','SAVINGS','INVESTMENT','RETIREMENT','OTHER'
    institution         VARCHAR(255),
    account_number      VARCHAR(100),  -- account number in plain-ish text (anti-pattern)
    balance             NUMERIC(12, 2),
    monthly_deposit     NUMERIC(10, 2),
    verification_status VARCHAR(50) DEFAULT 'PENDING',
    -- Denormalized borrower info (anti-pattern)
    borrower_name       VARCHAR(255),
    created_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS credit_reports (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    bureau              VARCHAR(50),  -- 'EQUIFAX','EXPERIAN','TRANSUNION' -- no CHECK
    score               INTEGER,
    report_date         DATE,
    report_data         TEXT,  -- entire credit report as TEXT blob (anti-pattern)
    pulled_by           UUID REFERENCES agents(id),
    expiry_date         DATE,
    -- Denormalized borrower info (anti-pattern)
    borrower_name       VARCHAR(255),
    borrower_ssn_last4  VARCHAR(10),
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ============================================================================
-- UNDERWRITING TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS underwriting_decisions (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    underwriter_id      UUID REFERENCES agents(id),  -- agent used as underwriter (anti-pattern: overloaded table)
    decision            VARCHAR(50),  -- 'APPROVED','APPROVED_WITH_CONDITIONS','SUSPENDED','DENIED'
    conditions          TEXT,  -- JSON stored as TEXT (anti-pattern)
    dti_ratio           NUMERIC(5, 2),
    ltv_ratio           NUMERIC(5, 2),
    risk_score          NUMERIC(5, 2),
    notes               TEXT,
    decision_date       TIMESTAMP,
    -- Denormalized loan info (anti-pattern)
    loan_amount         NUMERIC(12, 2),
    loan_type           VARCHAR(100),
    borrower_name       VARCHAR(255),
    property_address    VARCHAR(500),
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS underwriting_conditions (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    decision_id     UUID REFERENCES underwriting_decisions(id),
    condition_type  VARCHAR(100),  -- 'PRIOR_TO_DOC','PRIOR_TO_FUND','PRIOR_TO_CLOSE' -- no CHECK
    description     TEXT,
    status          VARCHAR(50) DEFAULT 'PENDING',  -- 'PENDING','SATISFIED','WAIVED' -- no CHECK
    satisfied_date  DATE,
    document_id     UUID REFERENCES client_documents(id),
    assigned_to     UUID REFERENCES agents(id),
    due_date        DATE,
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ============================================================================
-- APPRAISAL TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS appraisal_orders (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    property_id         UUID REFERENCES properties(id),
    appraiser_name      VARCHAR(255),
    appraiser_license   VARCHAR(100),
    appraiser_company   VARCHAR(255),
    order_date          DATE,
    due_date            DATE,
    completed_date      DATE,
    status              VARCHAR(50) DEFAULT 'ORDERED',  -- 'ORDERED','SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED'
    fee                 NUMERIC(8, 2),
    rush_fee            NUMERIC(8, 2),
    -- Denormalized property info (anti-pattern)
    property_address    VARCHAR(500),
    property_type       VARCHAR(50),
    notes               TEXT,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS appraisal_reports (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    appraisal_order_id  UUID REFERENCES appraisal_orders(id),
    appraised_value     NUMERIC(12, 2),
    approach_used       VARCHAR(100),  -- 'SALES_COMPARISON','COST','INCOME'
    condition_rating    VARCHAR(50),
    quality_rating      VARCHAR(50),
    report_date         DATE,
    effective_date      DATE,
    report_data         TEXT,  -- full report as TEXT blob (anti-pattern)
    -- Denormalized property info (anti-pattern)
    property_address    VARCHAR(500),
    property_sqft       INTEGER,
    property_beds       INTEGER,
    property_baths      NUMERIC(3, 1),
    notes               TEXT,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS comparable_sales (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    appraisal_report_id UUID REFERENCES appraisal_reports(id),
    address             VARCHAR(500),
    sale_price          NUMERIC(12, 2),
    sale_date           DATE,
    sqft                INTEGER,
    beds                INTEGER,
    baths               NUMERIC(3, 1),
    lot_size            NUMERIC(10, 2),
    year_built          INTEGER,
    distance_miles      NUMERIC(5, 2),
    adjustments         TEXT,  -- JSON stored as TEXT (anti-pattern)
    adjusted_price      NUMERIC(12, 2),
    data_source         VARCHAR(100),
    created_at          TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- CLOSING / SETTLEMENT TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS closing_details (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    listing_id          UUID REFERENCES listings(id),
    closing_date        DATE,
    closing_time        TIME,
    closing_location    VARCHAR(500),
    closing_agent_name  VARCHAR(255),
    closing_agent_email VARCHAR(255),
    status              VARCHAR(50) DEFAULT 'SCHEDULED',  -- 'SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED','DELAYED'
    total_closing_costs NUMERIC(10, 2),
    seller_credits      NUMERIC(10, 2),
    buyer_credits       NUMERIC(10, 2),
    proration_date      DATE,
    -- Denormalized info from everywhere (anti-pattern: extreme denormalization)
    property_address    VARCHAR(500),
    buyer_name          VARCHAR(255),
    seller_name         VARCHAR(255),
    loan_amount         NUMERIC(12, 2),
    sale_price          NUMERIC(12, 2),
    notes               TEXT,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS escrow_accounts (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    closing_id          UUID REFERENCES closing_details(id),
    account_number      VARCHAR(100),
    balance             NUMERIC(10, 2) DEFAULT 0,
    monthly_payment     NUMERIC(10, 2),
    property_tax_reserve    NUMERIC(10, 2),
    insurance_reserve       NUMERIC(10, 2),
    pmi_reserve             NUMERIC(10, 2),
    cushion_months      INTEGER DEFAULT 2,
    status              VARCHAR(50) DEFAULT 'ACTIVE',
    -- Denormalized info (anti-pattern)
    borrower_name       VARCHAR(255),
    property_address    VARCHAR(500),
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS title_reports (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    closing_id      UUID REFERENCES closing_details(id),
    title_company   VARCHAR(255),
    title_number    VARCHAR(100),
    status          VARCHAR(50) DEFAULT 'PENDING',  -- 'PENDING','CLEAR','LIEN_FOUND','EXCEPTION' -- no CHECK
    issues          TEXT,  -- JSON stored as TEXT (anti-pattern)
    lien_amount     NUMERIC(12, 2),
    report_date     DATE,
    effective_date  DATE,
    -- Denormalized property info (anti-pattern)
    property_address VARCHAR(500),
    owner_name      VARCHAR(255),
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS closing_documents (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    closing_id      UUID REFERENCES closing_details(id),
    document_type   VARCHAR(100),  -- 'CLOSING_DISCLOSURE','DEED','NOTE','MORTGAGE','TITLE_INSURANCE' -- no CHECK
    file_name       VARCHAR(500),
    file_path       VARCHAR(500),  -- plain file system path (anti-pattern)
    file_size_bytes BIGINT,
    signed          BOOLEAN DEFAULT FALSE,
    signed_date     TIMESTAMP,
    signed_by       VARCHAR(255),
    notarized       BOOLEAN DEFAULT FALSE,
    notary_name     VARCHAR(255),
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- LOAN SERVICING TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS loan_payments (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    payment_number      INTEGER,
    due_date            DATE,
    paid_date           DATE,
    principal_amount    NUMERIC(10, 2),
    interest_amount     NUMERIC(10, 2),
    escrow_amount       NUMERIC(10, 2),
    total_amount        NUMERIC(10, 2),
    additional_principal NUMERIC(10, 2) DEFAULT 0,
    status              VARCHAR(50) DEFAULT 'DUE',  -- 'DUE','PAID','LATE','PARTIAL','NSF'
    late_fee            NUMERIC(8, 2) DEFAULT 0,
    payment_method      VARCHAR(50),
    confirmation_number VARCHAR(100),
    -- Denormalized borrower info (anti-pattern)
    borrower_name       VARCHAR(255),
    loan_amount         NUMERIC(12, 2),
    created_at          TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment_schedules (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    loan_application_id UUID REFERENCES loan_applications(id),
    payment_number      INTEGER,
    due_date            DATE,
    principal           NUMERIC(10, 2),
    interest            NUMERIC(10, 2),
    escrow              NUMERIC(10, 2),
    total               NUMERIC(10, 2),
    remaining_balance   NUMERIC(12, 2),
    cumulative_interest NUMERIC(12, 2),
    cumulative_principal NUMERIC(12, 2),
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS escrow_disbursements (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    escrow_account_id   UUID REFERENCES escrow_accounts(id),
    disbursement_type   VARCHAR(100),  -- 'PROPERTY_TAX','HOMEOWNERS_INSURANCE','PMI','HOA' -- no CHECK
    amount              NUMERIC(10, 2),
    payee               VARCHAR(255),
    payee_account       VARCHAR(100),
    paid_date           DATE,
    period_covered      VARCHAR(100),
    check_number        VARCHAR(50),
    confirmation        VARCHAR(100),
    -- Denormalized escrow info (anti-pattern)
    property_address    VARCHAR(500),
    borrower_name       VARCHAR(255),
    notes               TEXT,
    created_at          TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- ADMIN / SYSTEM TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_logs (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID,  -- intentionally not FK-constrained (anti-pattern: could be agent or client or system)
    user_name       VARCHAR(255),
    action          VARCHAR(100),
    resource_type   VARCHAR(100),
    resource_id     VARCHAR(255),
    old_value       TEXT,  -- entire old state as TEXT (anti-pattern)
    new_value       TEXT,  -- entire new state as TEXT (anti-pattern)
    ip_address      VARCHAR(50),
    user_agent      VARCHAR(500),
    session_id      VARCHAR(255),
    timestamp       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS system_settings (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    setting_key     VARCHAR(255) UNIQUE NOT NULL,
    setting_value   TEXT,
    category        VARCHAR(100),
    description     TEXT,
    is_encrypted    BOOLEAN DEFAULT FALSE,
    updated_by      UUID,  -- not FK-constrained (anti-pattern)
    updated_at      TIMESTAMP DEFAULT NOW(),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS notifications (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID,  -- not FK-constrained (anti-pattern)
    title           VARCHAR(255),
    message         TEXT,
    type            VARCHAR(50),  -- 'INFO','WARNING','ERROR','SUCCESS','TASK'
    is_read         BOOLEAN DEFAULT FALSE,
    read_at         TIMESTAMP,
    link            VARCHAR(500),
    -- Denormalized user info (anti-pattern)
    user_name       VARCHAR(255),
    user_email      VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
