-- ==============================================================================
-- HomeCare Platform — Supabase Development Seed Data
-- Target: Supabase Local / Staging / Development Testing
-- Generated: 2026-11-11
--
-- Contains 4 canonical test users for GoTrue Auth with password: Homecare@2026
--   1. admin@homecare.com       (Role: ADMIN)
--   2. org@homecare.com         (Role: ORGANIZATION)
--   3. technician@homecare.com  (Role: TECHNICIAN)
--   4. customer@homecare.com    (Role: CUSTOMER)
-- ==============================================================================

DO $$
DECLARE
    v_admin_id UUID := 'a0000000-0000-0000-0000-000000000001';
    v_org_id UUID   := 'a0000000-0000-0000-0000-000000000002';
    v_tech_id UUID  := 'a0000000-0000-0000-0000-000000000003';
    v_cust_id UUID  := 'a0000000-0000-0000-0000-000000000004';
    v_password_hash TEXT;
BEGIN
    -- Bcrypt hash for password 'Homecare@2026'
    v_password_hash := crypt('Homecare@2026', gen_salt('bf', 10));

    -- 1. Insert/Update auth.users
    INSERT INTO auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, recovery_sent_at, last_sign_in_at,
        raw_app_meta_data, raw_user_meta_data, created_at, updated_at
    ) VALUES
        ('00000000-0000-0000-0000-000000000000', v_admin_id, 'authenticated', 'authenticated',
         'admin@homecare.com', v_password_hash, now(), now(), now(),
         '{"provider":"email","providers":["email"]}',
         '{"full_name":"System Administrator","phone_number":"0900000001"}',
         now(), now()),
        ('00000000-0000-0000-0000-000000000000', v_org_id, 'authenticated', 'authenticated',
         'org@homecare.com', v_password_hash, now(), now(), now(),
         '{"provider":"email","providers":["email"]}',
         '{"full_name":"Dien May Xanh Official","phone_number":"0900000002"}',
         now(), now()),
        ('00000000-0000-0000-0000-000000000000', v_tech_id, 'authenticated', 'authenticated',
         'technician@homecare.com', v_password_hash, now(), now(), now(),
         '{"provider":"email","providers":["email"]}',
         '{"full_name":"Nguyen Van Ky Thuat","phone_number":"0900000003"}',
         now(), now()),
        ('00000000-0000-0000-0000-000000000000', v_cust_id, 'authenticated', 'authenticated',
         'customer@homecare.com', v_password_hash, now(), now(), now(),
         '{"provider":"email","providers":["email"]}',
         '{"full_name":"Tran Thi Khach Hang","phone_number":"0900000004"}',
         now(), now())
    ON CONFLICT (id) DO UPDATE SET
        encrypted_password = EXCLUDED.encrypted_password,
        raw_user_meta_data = EXCLUDED.raw_user_meta_data,
        updated_at = now();

    -- 2. Insert/Update auth.identities (Required for Supabase GoTrue password sign-in)
    INSERT INTO auth.identities (
        id, user_id, identity_data, provider, provider_id, last_sign_in_at, created_at, updated_at
    ) VALUES
        (v_admin_id, v_admin_id, jsonb_build_object('sub', v_admin_id::text, 'email', 'admin@homecare.com'), 'email', v_admin_id::text, now(), now(), now()),
        (v_org_id,   v_org_id,   jsonb_build_object('sub', v_org_id::text,   'email', 'org@homecare.com'),   'email', v_org_id::text,   now(), now(), now()),
        (v_tech_id,  v_tech_id,  jsonb_build_object('sub', v_tech_id::text,  'email', 'technician@homecare.com'), 'email', v_tech_id::text, now(), now(), now()),
        (v_cust_id,  v_cust_id,  jsonb_build_object('sub', v_cust_id::text,  'email', 'customer@homecare.com'),   'email', v_cust_id::text,   now(), now(), now())
    ON CONFLICT (provider, provider_id) DO UPDATE SET
        identity_data = EXCLUDED.identity_data,
        updated_at = now();

    -- 3. Insert/Update public.profiles (Status ACTIVE)
    INSERT INTO public.profiles (id, full_name, phone_number, status) VALUES
        (v_admin_id, 'System Administrator', '0900000001', 'ACTIVE'),
        (v_org_id,   'Dien May Xanh Official', '0900000002', 'ACTIVE'),
        (v_tech_id,  'Nguyen Van Ky Thuat', '0900000003', 'ACTIVE'),
        (v_cust_id,  'Tran Thi Khach Hang', '0900000004', 'ACTIVE')
    ON CONFLICT (id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        phone_number = EXCLUDED.phone_number,
        status = EXCLUDED.status,
        updated_at = now();

    -- 4. Assign Roles explicitly in public.user_roles
    DELETE FROM public.user_roles WHERE user_id IN (v_admin_id, v_org_id, v_tech_id, v_cust_id);

    INSERT INTO public.user_roles (user_id, role_id) VALUES
        (v_admin_id, (SELECT id FROM public.roles WHERE code = 'ADMIN')),
        (v_org_id,   (SELECT id FROM public.roles WHERE code = 'ORGANIZATION')),
        (v_tech_id,  (SELECT id FROM public.roles WHERE code = 'TECHNICIAN')),
        (v_cust_id,  (SELECT id FROM public.roles WHERE code = 'CUSTOMER'));

END $$;
