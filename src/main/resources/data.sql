-- =============================================================================
-- HomeLend Pro - Seed Data
-- Platform: Real Estate + Mortgage
-- Database: PostgreSQL
-- Idempotent: All inserts use ON CONFLICT (id) DO NOTHING
-- =============================================================================

-- =============================================================================
-- 1. ROLES (2 entries)
-- =============================================================================
-- UUID prefix: 01000000-...

INSERT INTO roles (id, name, description, created_at, updated_at)
VALUES
  ('01000000-0000-0000-0000-000000000001', 'ADMIN',
   'Full system administrator with unrestricted access to all HomeLend Pro features',
   NOW(), NOW()),
  ('01000000-0000-0000-0000-000000000002', 'AGENT',
   'Licensed real estate agent or loan officer with client and listing management access',
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 2. BROKERAGES (3 entries)
-- =============================================================================
-- UUID prefix: 10000000-...

INSERT INTO brokerages (id, name, address_line1, address_line2, city, state, zip_code, phone, email, license_number, website, created_at, updated_at)
VALUES
  ('10000000-0000-0000-0000-000000000001',
   'Summit Realty Group',
   '1600 Champa St', 'Suite 400', 'Denver', 'CO', '80202',
   '(303) 555-1200', 'info@summitrealty.com', 'BRK-2024-88401',
   'https://www.summitrealty.com', NOW(), NOW()),

  ('10000000-0000-0000-0000-000000000002',
   'Pacific Coast Lending',
   '9601 Wilshire Blvd', 'Suite 720', 'Beverly Hills', 'CA', '90210',
   '(310) 555-3400', 'contact@pacificcoastlending.com', 'BRK-2024-90210',
   'https://www.pacificcoastlending.com', NOW(), NOW()),

  ('10000000-0000-0000-0000-000000000003',
   'Liberty Home Partners',
   '1500 Market St', '12th Floor', 'Philadelphia', 'PA', '19102',
   '(215) 555-6700', 'hello@libertyhomepartners.com', 'BRK-2024-19103',
   'https://www.libertyhomepartners.com', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 3. AGENTS (6 entries)
-- =============================================================================
-- UUID prefix: 20000000-...
-- Denormalized: brokerage_name, brokerage_phone

INSERT INTO agents (id, first_name, last_name, email, phone, license_number, brokerage_id, hire_date, is_active, commission_rate, bio, photo_url, brokerage_name, brokerage_phone, created_at, updated_at)
VALUES
  -- Real Estate Agents
  ('20000000-0000-0000-0000-000000000001',
   'Marcus', 'Delgado', 'marcus.delgado@summitrealty.com', '(303) 555-1201',
   'RE-CO-2019-44210',
   '10000000-0000-0000-0000-000000000001',  -- Summit Realty Group
   '2019-03-15', true, 2.75,
   'Top-producing Denver agent specializing in Park Hill and City Park neighborhoods. 6 years of experience helping families find their dream homes.',
   'https://cdn.homelendpro.com/agents/marcus-delgado.jpg',
   'Summit Realty Group', '(303) 555-1200',
   NOW(), NOW()),

  ('20000000-0000-0000-0000-000000000002',
   'Jennifer', 'Nakamura', 'jennifer.nakamura@pacificcoastlending.com', '(310) 555-3401',
   'RE-CA-2017-67832',
   '10000000-0000-0000-0000-000000000002',  -- Pacific Coast Lending
   '2017-08-01', true, 2.50,
   'Beverly Hills and Westside luxury specialist with over $50M in career sales. Fluent in English and Japanese.',
   'https://cdn.homelendpro.com/agents/jennifer-nakamura.jpg',
   'Pacific Coast Lending', '(310) 555-3400',
   NOW(), NOW()),

  ('20000000-0000-0000-0000-000000000003',
   'David', 'Okonkwo', 'david.okonkwo@libertyhomepartners.com', '(215) 555-6701',
   'RE-PA-2020-15498',
   '10000000-0000-0000-0000-000000000003',  -- Liberty Home Partners
   '2020-01-10', true, 3.00,
   'Philadelphia historic homes expert with deep knowledge of Society Hill, Old City, and Rittenhouse neighborhoods.',
   'https://cdn.homelendpro.com/agents/david-okonkwo.jpg',
   'Liberty Home Partners', '(215) 555-6700',
   NOW(), NOW()),

  -- Loan Officers
  ('20000000-0000-0000-0000-000000000004',
   'Sarah', 'Mitchell', 'sarah.mitchell@summitrealty.com', '(303) 555-1202',
   'LO-CO-2018-33109',
   '10000000-0000-0000-0000-000000000001',  -- Summit Realty Group
   '2018-06-20', true, 1.00,
   'Senior loan officer with expertise in FHA, VA, and conventional lending. Known for closing complex deals on time.',
   'https://cdn.homelendpro.com/agents/sarah-mitchell.jpg',
   'Summit Realty Group', '(303) 555-1200',
   NOW(), NOW()),

  ('20000000-0000-0000-0000-000000000005',
   'Robert', 'Fitzgerald', 'robert.fitzgerald@pacificcoastlending.com', '(310) 555-3402',
   'LO-CA-2016-51207',
   '10000000-0000-0000-0000-000000000002',  -- Pacific Coast Lending
   '2016-11-05', true, 1.00,
   'Jumbo and high-net-worth lending specialist serving the greater Los Angeles area. 10+ years in mortgage banking.',
   'https://cdn.homelendpro.com/agents/robert-fitzgerald.jpg',
   'Pacific Coast Lending', '(310) 555-3400',
   NOW(), NOW()),

  -- Processor
  ('20000000-0000-0000-0000-000000000006',
   'Priya', 'Sharma', 'priya.sharma@libertyhomepartners.com', '(215) 555-6702',
   'UW-PA-2015-09823',
   '10000000-0000-0000-0000-000000000003',  -- Liberty Home Partners
   '2015-04-22', true, 0.50,
   'Experienced loan processor and underwriter with a meticulous approach to documentation review and risk assessment.',
   'https://cdn.homelendpro.com/agents/priya-sharma.jpg',
   'Liberty Home Partners', '(215) 555-6700',
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 4. CLIENTS (5 entries)
-- =============================================================================
-- UUID prefix: 30000000-...
-- Denormalized: agent_name, agent_email, agent_phone

INSERT INTO clients (id, first_name, last_name, email, phone, ssn_encrypted, date_of_birth, address_line1, address_line2, city, state, zip_code, client_type, assigned_agent_id, agent_name, agent_email, agent_phone, preferred_contact_method, notes, created_at, updated_at)
VALUES
  -- Buyers
  ('30000000-0000-0000-0000-000000000001',
   'Michael', 'Torres', 'michael.torres@gmail.com', '(720) 555-8831',
   'enc:AES256:a1b2c3d4e5f6', '1988-07-14',
   '4521 Vine St', 'Apt 3B', 'Denver', 'CO', '80205',
   'BUYER',
   '20000000-0000-0000-0000-000000000001',  -- Marcus Delgado
   'Marcus Delgado', 'marcus.delgado@summitrealty.com', '(303) 555-1201',
   'EMAIL',
   'First-time homebuyer looking for a 3-4 bedroom in Park Hill or Stapleton. Pre-approved up to $550K. Wants to close by May 2026.',
   NOW(), NOW()),

  ('30000000-0000-0000-0000-000000000002',
   'Aisha', 'Patel', 'aisha.patel@outlook.com', '(213) 555-2294',
   'enc:AES256:f6e5d4c3b2a1', '1992-11-03',
   '820 S Olive St', 'Unit 1407', 'Los Angeles', 'CA', '90014',
   'BUYER',
   '20000000-0000-0000-0000-000000000002',  -- Jennifer Nakamura
   'Jennifer Nakamura', 'jennifer.nakamura@pacificcoastlending.com', '(310) 555-3401',
   'PHONE',
   'Relocating from Chicago for work. Interested in Santa Monica or Westside condos. Budget up to $2M. Pre-approved with jumbo lender.',
   NOW(), NOW()),

  -- Seller
  ('30000000-0000-0000-0000-000000000003',
   'Gregory', 'Hanson', 'greg.hanson@yahoo.com', '(267) 555-4150',
   'enc:AES256:b3c4d5e6f7g8', '1975-02-28',
   '337 Pine St', NULL, 'Philadelphia', 'PA', '19106',
   'SELLER',
   '20000000-0000-0000-0000-000000000003',  -- David Okonkwo
   'David Okonkwo', 'david.okonkwo@libertyhomepartners.com', '(215) 555-6701',
   'EMAIL',
   'Selling primary residence due to job relocation to Chicago. Motivated seller, open to reasonable offers. Wants to close within 60 days.',
   NOW(), NOW()),

  -- Borrowers (applying for mortgages)
  ('30000000-0000-0000-0000-000000000004',
   'Lisa', 'Carmichael', 'lisa.carmichael@protonmail.com', '(303) 555-9072',
   'enc:AES256:d5e6f7g8h9i0', '1985-09-19',
   '1901 Wazee St', 'Apt 6D', 'Denver', 'CO', '80202',
   'BORROWER',
   '20000000-0000-0000-0000-000000000004',  -- Sarah Mitchell (Loan Officer)
   'Sarah Mitchell', 'sarah.mitchell@summitrealty.com', '(303) 555-1202',
   'TEXT',
   'FHA borrower. Stable employment at Denver Health for 8 years. Credit score 695 -- working on improvement. Targeting the Fillmore St condo.',
   NOW(), NOW()),

  ('30000000-0000-0000-0000-000000000005',
   'James', 'Whitfield', 'j.whitfield@icloud.com', '(310) 555-7763',
   'enc:AES256:h9i0j1k2l3m4', '1990-04-06',
   '2200 Colorado Ave', 'Unit 5', 'Santa Monica', 'CA', '90404',
   'BORROWER',
   '20000000-0000-0000-0000-000000000005',  -- Robert Fitzgerald (Loan Officer)
   'Robert Fitzgerald', 'robert.fitzgerald@pacificcoastlending.com', '(310) 555-3402',
   'EMAIL',
   'High-income tech executive seeking jumbo loan for oceanfront condo. Excellent credit (758). Large liquid reserves.',
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 5. PROPERTIES (10 entries: various types across US cities)
-- =============================================================================
-- UUID prefix: 40000000-...

INSERT INTO properties (id, address_line1, address_line2, city, state, zip_code, county, latitude, longitude, beds, baths, sqft, lot_size, year_built, property_type, description, parking_spaces, garage_type, hoa_fee, zoning, parcel_number, last_sold_price, last_sold_date, current_tax_amount, created_at, updated_at)
VALUES
  -- Denver Metro
  ('40000000-0000-0000-0000-000000000001',
   '2847 Clermont St', NULL, 'Denver', 'CO', '80207', 'Denver',
   39.7545, -104.9410,
   4, 3.0, 2450, 6200.00, 1962,
   'SINGLE_FAMILY',
   'Beautifully updated Park Hill ranch with finished basement, hardwood floors throughout, and a sun-drenched backyard with mature landscaping.',
   2, 'ATTACHED', 0.00, 'R-2', '05-0621-14-009',
   425000.00, '2018-06-15', 4280.00,
   NOW(), NOW()),

  ('40000000-0000-0000-0000-000000000002',
   '1650 Fillmore St', 'Unit 504', 'Denver', 'CO', '80206', 'Denver',
   39.7460, -104.9560,
   2, 2.0, 1180, 0.00, 2018,
   'CONDO',
   'Modern City Park condo with floor-to-ceiling windows, quartz counters, in-unit laundry, and rooftop deck with mountain views.',
   1, 'UNDERGROUND', 385.00, 'C-MX-8', '05-0522-07-033',
   395000.00, '2020-09-22', 3120.00,
   NOW(), NOW()),

  -- Los Angeles / Southern California
  ('40000000-0000-0000-0000-000000000003',
   '14522 Greenleaf St', NULL, 'Sherman Oaks', 'CA', '91403', 'Los Angeles',
   34.1530, -118.4490,
   3, 2.0, 1780, 7500.00, 1955,
   'SINGLE_FAMILY',
   'Classic mid-century single family in the heart of Sherman Oaks with pool, updated kitchen, and large private yard.',
   2, 'DETACHED', 0.00, 'R-1', '2261-014-005',
   820000.00, '2017-03-10', 7640.00,
   NOW(), NOW()),

  ('40000000-0000-0000-0000-000000000004',
   '1234 Ocean Ave', 'Unit 802', 'Santa Monica', 'CA', '90401', 'Los Angeles',
   34.0130, -118.4960,
   2, 2.0, 1350, 0.00, 2015,
   'CONDO',
   'Luxury oceanfront condo with unobstructed Pacific views, chef kitchen, spa-inspired bathroom, and concierge services.',
   1, 'UNDERGROUND', 625.00, 'C-3', '4281-023-018',
   1650000.00, '2019-11-04', 9850.00,
   NOW(), NOW()),

  ('40000000-0000-0000-0000-000000000005',
   '3309 Keystone Ave', 'Unit B', 'Los Angeles', 'CA', '90034', 'Los Angeles',
   34.0260, -118.3970,
   3, 3.0, 1620, 1200.00, 2020,
   'TOWNHOUSE',
   'Newer construction Palms townhouse with rooftop deck, open floor plan, EV charger, and smart home features.',
   2, 'ATTACHED', 290.00, 'R-3', '4217-008-042',
   780000.00, '2021-02-18', 6320.00,
   NOW(), NOW()),

  -- Philadelphia Metro
  ('40000000-0000-0000-0000-000000000006',
   '337 Pine St', NULL, 'Philadelphia', 'PA', '19106', 'Philadelphia',
   39.9445, -75.1480,
   3, 3.0, 2100, 900.00, 1840,
   'TOWNHOUSE',
   'Historic Society Hill townhouse with exposed brick, original hardwood floors, chef kitchen renovation, and private courtyard garden.',
   0, 'NONE', 0.00, 'RM-1', '05-04-2350-00',
   510000.00, '2014-08-30', 5890.00,
   NOW(), NOW()),

  ('40000000-0000-0000-0000-000000000007',
   '1500 Chestnut St', 'Unit 18F', 'Philadelphia', 'PA', '19102', 'Philadelphia',
   39.9510, -75.1660,
   1, 1.0, 820, 0.00, 2010,
   'CONDO',
   'Center City luxury high-rise with panoramic skyline views, doorman, fitness center, and steps from Rittenhouse Square.',
   1, 'UNDERGROUND', 510.00, 'CMX-5', '88-10-1440-00',
   320000.00, '2016-05-12', 3450.00,
   NOW(), NOW()),

  -- Austin, TX
  ('40000000-0000-0000-0000-000000000008',
   '4710 Banister Ln', NULL, 'Austin', 'TX', '78745', 'Travis',
   30.2150, -97.7780,
   3, 2.0, 1650, 8100.00, 1978,
   'SINGLE_FAMILY',
   'Charming South Austin bungalow with wraparound porch, updated HVAC, new roof (2024), and large fenced backyard with workshop.',
   2, 'DETACHED', 0.00, 'SF-3', '03-0771-0204-0000',
   340000.00, '2019-07-22', 5200.00,
   NOW(), NOW()),

  -- Raleigh, NC
  ('40000000-0000-0000-0000-000000000009',
   '509 W Cabarrus St', NULL, 'Raleigh', 'NC', '27603', 'Wake',
   35.7740, -78.6460,
   3, 3.0, 1890, 1500.00, 2022,
   'TOWNHOUSE',
   'Brand-new warehouse district townhome with industrial-chic finishes, walk-in pantry, and rooftop terrace near downtown Raleigh.',
   2, 'ATTACHED', 175.00, 'RX-3', '1703-86-5491',
   NULL, NULL, 4100.00,
   NOW(), NOW()),

  -- Scottsdale, AZ
  ('40000000-0000-0000-0000-000000000010',
   '8602 E Paraiso Dr', NULL, 'Scottsdale', 'AZ', '85255', 'Maricopa',
   33.6490, -111.8980,
   5, 4.0, 3800, 18500.00, 2008,
   'SINGLE_FAMILY',
   'Stunning desert contemporary in North Scottsdale with infinity pool, home theater, gourmet kitchen, and Camelback Mountain views.',
   3, 'ATTACHED', 145.00, 'R1-35', '217-40-089',
   1180000.00, '2020-01-15', 6750.00,
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 6. LISTINGS (8 active listings linked to properties and agents)
-- =============================================================================
-- UUID prefix: 50000000-...
-- Denormalized: property_address, property_city, property_state, property_zip, property_beds, property_baths, property_sqft

INSERT INTO listings (id, property_id, agent_id, list_price, original_price, status, mls_number, listed_date, expiry_date, sold_date, sold_price, days_on_market, property_address, property_city, property_state, property_zip, property_beds, property_baths, property_sqft, description, virtual_tour_url, created_at, updated_at)
VALUES
  ('50000000-0000-0000-0000-000000000001',
   '40000000-0000-0000-0000-000000000001',  -- 2847 Clermont St, Denver
   '20000000-0000-0000-0000-000000000001',  -- Marcus Delgado
   625000.00, 649000.00, 'ACTIVE',
   'MLS-CO-2026-001542', '2026-01-15', '2026-07-15',
   NULL, NULL, 47,
   '2847 Clermont St', 'Denver', 'CO', '80207',
   4, 3.0, 2450,
   'Beautifully updated Park Hill ranch with finished basement, hardwood floors throughout, and a sun-drenched backyard with mature landscaping.',
   'https://tours.homelendpro.com/2847-clermont',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000002',  -- 1650 Fillmore St #504, Denver
   '20000000-0000-0000-0000-000000000001',  -- Marcus Delgado
   485000.00, 485000.00, 'ACTIVE',
   'MLS-CO-2026-001897', '2026-02-01', '2026-08-01',
   NULL, NULL, 30,
   '1650 Fillmore St, Unit 504', 'Denver', 'CO', '80206',
   2, 2.0, 1180,
   'Modern City Park condo with floor-to-ceiling windows, quartz counters, in-unit laundry, and rooftop deck with mountain views.',
   'https://tours.homelendpro.com/1650-fillmore-504',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000003',
   '40000000-0000-0000-0000-000000000003',  -- 14522 Greenleaf St, Sherman Oaks
   '20000000-0000-0000-0000-000000000002',  -- Jennifer Nakamura
   1150000.00, 1195000.00, 'ACTIVE',
   'MLS-CA-2026-034821', '2026-01-20', '2026-07-20',
   NULL, NULL, 42,
   '14522 Greenleaf St', 'Sherman Oaks', 'CA', '91403',
   3, 2.0, 1780,
   'Classic mid-century single family in the heart of Sherman Oaks with pool, updated kitchen, and large private yard.',
   'https://tours.homelendpro.com/14522-greenleaf',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000004',
   '40000000-0000-0000-0000-000000000004',  -- 1234 Ocean Ave #802, Santa Monica
   '20000000-0000-0000-0000-000000000002',  -- Jennifer Nakamura
   1875000.00, 1875000.00, 'ACTIVE',
   'MLS-CA-2026-035190', '2026-02-10', '2026-08-10',
   NULL, NULL, 21,
   '1234 Ocean Ave, Unit 802', 'Santa Monica', 'CA', '90401',
   2, 2.0, 1350,
   'Luxury oceanfront condo with unobstructed Pacific views, chef kitchen, spa-inspired bathroom, and concierge services.',
   'https://tours.homelendpro.com/1234-ocean-802',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000005',
   '40000000-0000-0000-0000-000000000005',  -- 3309 Keystone Ave Unit B, LA
   '20000000-0000-0000-0000-000000000002',  -- Jennifer Nakamura
   895000.00, 925000.00, 'ACTIVE',
   'MLS-CA-2026-035444', '2026-02-18', '2026-08-18',
   NULL, NULL, 13,
   '3309 Keystone Ave, Unit B', 'Los Angeles', 'CA', '90034',
   3, 3.0, 1620,
   'Newer construction Palms townhouse with rooftop deck, open floor plan, EV charger, and smart home features.',
   'https://tours.homelendpro.com/3309-keystone-b',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000006',
   '40000000-0000-0000-0000-000000000006',  -- 337 Pine St, Philadelphia
   '20000000-0000-0000-0000-000000000003',  -- David Okonkwo
   749000.00, 789000.00, 'ACTIVE',
   'MLS-PA-2026-012003', '2026-01-08', '2026-07-08',
   NULL, NULL, 54,
   '337 Pine St', 'Philadelphia', 'PA', '19106',
   3, 3.0, 2100,
   'Historic Society Hill townhouse with exposed brick, original hardwood floors, chef kitchen renovation, and private courtyard garden. Motivated seller -- recently relocated.',
   'https://tours.homelendpro.com/337-pine',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000007',
   '40000000-0000-0000-0000-000000000008',  -- 4710 Banister Ln, Austin
   '20000000-0000-0000-0000-000000000001',  -- Marcus Delgado (referral listing)
   475000.00, 475000.00, 'ACTIVE',
   'MLS-TX-2026-098331', '2026-02-22', '2026-08-22',
   NULL, NULL, 9,
   '4710 Banister Ln', 'Austin', 'TX', '78745',
   3, 2.0, 1650,
   'Charming South Austin bungalow with wraparound porch, updated HVAC, new roof (2024), and large fenced backyard with workshop.',
   'https://tours.homelendpro.com/4710-banister',
   NOW(), NOW()),

  ('50000000-0000-0000-0000-000000000008',
   '40000000-0000-0000-0000-000000000010', -- 8602 E Paraiso Dr, Scottsdale
   '20000000-0000-0000-0000-000000000003',  -- David Okonkwo (referral listing)
   1495000.00, 1550000.00, 'ACTIVE',
   'MLS-AZ-2026-045672', '2026-02-05', '2026-08-05',
   NULL, NULL, 26,
   '8602 E Paraiso Dr', 'Scottsdale', 'AZ', '85255',
   5, 4.0, 3800,
   'Stunning desert contemporary in North Scottsdale with infinity pool, home theater, gourmet kitchen, and Camelback Mountain views.',
   'https://tours.homelendpro.com/8602-paraiso',
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 7. LOAN APPLICATIONS (3 entries at different stages)
-- =============================================================================
-- UUID prefix: 60000000-...
-- Denormalized: borrower_name, borrower_email, borrower_phone, borrower_ssn_encrypted, property_address, property_value

INSERT INTO loan_applications (id, borrower_id, co_borrower_id, property_id, loan_type, loan_purpose, loan_amount, interest_rate, loan_term_months, down_payment, down_payment_percent, loan_officer_id, processor_id, status, application_date, estimated_closing_date, actual_closing_date, borrower_name, borrower_email, borrower_phone, borrower_ssn_encrypted, property_address, property_value, monthly_payment, total_interest, apr, notes, created_at, updated_at)
VALUES
  -- Application 1: Pre-Approved, early stage
  ('60000000-0000-0000-0000-000000000001',
   '30000000-0000-0000-0000-000000000001',  -- Michael Torres (buyer)
   NULL,                                     -- No co-borrower
   '40000000-0000-0000-0000-000000000001',  -- 2847 Clermont St, Denver
   'CONVENTIONAL', 'PURCHASE',
   500000.00, 6.875, 360, 125000.00, 20.00,
   '20000000-0000-0000-0000-000000000004',  -- Sarah Mitchell (Loan Officer)
   NULL,                                     -- Not yet assigned to processor
   'PRE_APPROVED',
   '2026-01-20', '2026-05-15', NULL,
   'Michael Torres', 'michael.torres@gmail.com', '(720) 555-8831', 'enc:AES256:a1b2c3d4e5f6',
   '2847 Clermont St, Denver, CO 80207', 625000.00,
   3285.07, 682624.20, 7.012,
   'Borrower has stable W-2 employment history (5+ years). DTI ratio 28%. Awaiting signed purchase agreement to proceed to underwriting.',
   NOW(), NOW()),

  -- Application 2: In Underwriting
  ('60000000-0000-0000-0000-000000000002',
   '30000000-0000-0000-0000-000000000005',  -- James Whitfield (borrower)
   NULL,
   '40000000-0000-0000-0000-000000000004',  -- 1234 Ocean Ave #802, Santa Monica
   'JUMBO', 'PURCHASE',
   1500000.00, 7.125, 360, 375000.00, 20.00,
   '20000000-0000-0000-0000-000000000005',  -- Robert Fitzgerald (Loan Officer)
   '20000000-0000-0000-0000-000000000006',  -- Priya Sharma (Processor)
   'IN_UNDERWRITING',
   '2025-12-10', '2026-04-30', NULL,
   'James Whitfield', 'j.whitfield@icloud.com', '(310) 555-7763', 'enc:AES256:h9i0j1k2l3m4',
   '1234 Ocean Ave, Unit 802, Santa Monica, CA 90401', 1875000.00,
   10104.17, 2137501.20, 7.248,
   'Jumbo loan -- higher scrutiny required. Appraisal completed at $1,900,000. Awaiting final verification of employment and bank statements for last 60 days.',
   NOW(), NOW()),

  -- Application 3: Conditionally Approved (near closing)
  ('60000000-0000-0000-0000-000000000003',
   '30000000-0000-0000-0000-000000000004',  -- Lisa Carmichael (borrower)
   NULL,
   '40000000-0000-0000-0000-000000000002',  -- 1650 Fillmore St #504, Denver
   'FHA', 'PURCHASE',
   469225.00, 6.500, 360, 16975.00, 3.50,
   '20000000-0000-0000-0000-000000000004',  -- Sarah Mitchell (Loan Officer)
   '20000000-0000-0000-0000-000000000006',  -- Priya Sharma (Processor)
   'CONDITIONALLY_APPROVED',
   '2025-11-05', '2026-03-28', NULL,
   'Lisa Carmichael', 'lisa.carmichael@protonmail.com', '(303) 555-9072', 'enc:AES256:d5e6f7g8h9i0',
   '1650 Fillmore St, Unit 504, Denver, CO 80206', 485000.00,
   2965.33, 598294.80, 7.184,
   'FHA loan conditionally approved. Outstanding conditions: (1) updated pay stub dated within 30 days of closing, (2) proof of homeowner insurance binder, (3) clear-to-close from title company. Target closing March 28.',
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 8. SYSTEM SETTINGS (10 entries)
-- =============================================================================
-- UUID prefix: 70000000-...

INSERT INTO system_settings (id, setting_key, setting_value, category, description, is_encrypted, updated_by, updated_at, created_at)
VALUES
  ('70000000-0000-0000-0000-000000000001',
   'platform.name', 'HomeLend Pro', 'GENERAL',
   'Display name of the platform shown in headers and emails',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000002',
   'platform.version', '2.4.1', 'GENERAL',
   'Current application version number',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000003',
   'listing.default_expiration_days', '180', 'LISTINGS',
   'Default number of days before a listing expires from its creation date',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000004',
   'loan.max_dti_ratio', '43.0', 'LENDING',
   'Maximum allowable debt-to-income ratio (%) for conventional loan qualification',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000005',
   'loan.fha_min_down_payment_pct', '3.5', 'LENDING',
   'Minimum down payment percentage for FHA loans',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000006',
   'loan.conventional_min_down_payment_pct', '5.0', 'LENDING',
   'Minimum down payment percentage for conventional loans',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000007',
   'commission.default_rate', '2.5', 'AGENTS',
   'Default buyer/seller agent commission rate (%) applied to new listings',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000008',
   'notification.email_enabled', 'true', 'NOTIFICATIONS',
   'Toggle email notifications for system events (loan status changes, new listings, etc.)',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000009',
   'session.timeout_minutes', '30', 'SECURITY',
   'User session inactivity timeout in minutes before automatic logout',
   false, NULL, NOW(), NOW()),

  ('70000000-0000-0000-0000-000000000010',
   'integrations.mls_api_key', 'enc:AES256:x9y8z7w6v5u4t3s2r1q0', 'INTEGRATIONS',
   'API key for MLS data feed integration',
   true, NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
