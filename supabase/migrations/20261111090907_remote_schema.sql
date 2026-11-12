SET local check_function_bodies = off;

CREATE TABLE "public"."permissions" (
  "id"          uuid                     NOT NULL DEFAULT gen_random_uuid(),
  "code"        character varying(100)   NOT NULL,
  "name"        character varying(100)   NOT NULL,
  "module"      character varying(50)    NOT NULL,
  "description" text,
  "created_at"  timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "permissions_code_key" UNIQUE (code),
  CONSTRAINT "permissions_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."permissions"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."profiles" (
  "id"            uuid                     NOT NULL,
  "full_name"     character varying(150)   NOT NULL DEFAULT ''::character varying,
  "avatar_url"    text                     DEFAULT ''::text,
  "phone_number"  character varying(20)    DEFAULT ''::character varying,
  "date_of_birth" date,
  "gender"        character varying(20),
  "address"       text                     DEFAULT ''::text,
  "created_at"    timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at"    timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "profiles_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."profiles"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."role_permissions" (
  "role_id"       uuid                     NOT NULL,
  "permission_id" uuid                     NOT NULL,
  "created_at"    timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "role_permissions_pkey" PRIMARY KEY (role_id, permission_id)
);

ALTER TABLE "public"."role_permissions"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."roles" (
  "id"          uuid                     NOT NULL DEFAULT gen_random_uuid(),
  "code"        character varying(50)    NOT NULL,
  "name"        character varying(100)   NOT NULL,
  "description" text,
  "created_at"  timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "roles_code_key" UNIQUE (code),
  CONSTRAINT "roles_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."roles"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."user_roles" (
  "user_id"     uuid                     NOT NULL,
  "role_id"     uuid                     NOT NULL,
  "assigned_at" timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "user_roles_pkey" PRIMARY KEY (user_id, role_id)
);

ALTER TABLE "public"."user_roles"
  ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION public.custom_access_token_hook (
  event jsonb
)
  RETURNS jsonb
  LANGUAGE plpgsql
  STABLE
  SECURITY DEFINER
  SET search_path TO 'public'
  AS $function$
DECLARE
    v_user_id UUID;
    v_user_roles TEXT[];
    v_permissions TEXT[];
    v_claims JSONB;
BEGIN
    v_user_id := (event->>'user_id')::UUID;
    v_claims := event->'claims';

    -- 1. Query distinct role codes
    SELECT array_agg(DISTINCT r.code)
    INTO v_user_roles
    FROM public.user_roles ur
    JOIN public.roles r ON ur.role_id = r.id
    WHERE ur.user_id = v_user_id;

    -- 2. Query distinct permission codes via role_permissions
    SELECT array_agg(DISTINCT p.code)
    INTO v_permissions
    FROM public.user_roles ur
    JOIN public.role_permissions rp ON ur.role_id = rp.role_id
    JOIN public.permissions p ON rp.permission_id = p.id
    WHERE ur.user_id = v_user_id;

    -- Defaults to empty array if no roles or permissions
    IF v_user_roles IS NULL THEN
        v_user_roles := ARRAY[]::TEXT[];
    END IF;

    IF v_permissions IS NULL THEN
        v_permissions := ARRAY[]::TEXT[];
    END IF;

    -- 3. Inject claims into access token JWT
    v_claims := jsonb_set(v_claims, '{user_roles}', to_jsonb(v_user_roles));
    v_claims := jsonb_set(v_claims, '{permissions}', to_jsonb(v_permissions));

    -- Return updated event
    event := jsonb_set(event, '{claims}', v_claims);
    RETURN event;
END;
$function$;

REVOKE ALL ON FUNCTION "public"."custom_access_token_hook"(jsonb) FROM PUBLIC, "anon", "authenticated";

CREATE OR REPLACE FUNCTION public.handle_new_user()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path TO 'public'
  AS $function$
DECLARE
    v_customer_role_id UUID;
BEGIN
    -- 1. Create Profile row
    INSERT INTO public.profiles (id, full_name, avatar_url, phone_number)
    VALUES (
        new.id,
        COALESCE(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name', ''),
        COALESCE(new.raw_user_meta_data->>'avatar_url', ''),
        COALESCE(new.raw_user_meta_data->>'phone_number', new.raw_user_meta_data->>'phone', '')
    )
    ON CONFLICT (id) DO NOTHING;

    -- 2. Assign default CUSTOMER role
    SELECT id INTO v_customer_role_id FROM public.roles WHERE code = 'CUSTOMER';
    IF v_customer_role_id IS NOT NULL THEN
        INSERT INTO public.user_roles (user_id, role_id)
        VALUES (new.id, v_customer_role_id)
        ON CONFLICT (user_id, role_id) DO NOTHING;
    END IF;

    RETURN new;
END;
$function$;

CREATE OR REPLACE FUNCTION public.is_admin()
  RETURNS boolean
  LANGUAGE sql
  STABLE
  SECURITY DEFINER
  SET search_path TO 'public'
  AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_roles ur
        JOIN public.roles r ON ur.role_id = r.id
        WHERE ur.user_id = auth.uid() AND r.code = 'ADMIN'
    );
$function$;

CREATE OR REPLACE FUNCTION public.rls_auto_enable()
  RETURNS event_trigger
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path TO 'pg_catalog'
  AS $function$
DECLARE
  cmd record;
  tbl name;
BEGIN
  FOR cmd IN
    SELECT *
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      AND object_type IN ('table','partitioned table')
  LOOP
     IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public') AND cmd.schema_name NOT IN ('pg_catalog','information_schema') AND cmd.schema_name NOT LIKE 'pg_toast%' AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
      BEGIN
        SELECT c.relname INTO tbl FROM pg_catalog.pg_class c WHERE c.oid = cmd.objid;
        EXECUTE format('alter table if exists %I.%I enable row level security', cmd.schema_name, tbl);
        RAISE LOG 'rls_auto_enable: enabled RLS on %', cmd.object_identity;
      EXCEPTION
        WHEN OTHERS THEN
          RAISE LOG 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;
      END;
     ELSE
        RAISE LOG 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)', cmd.object_identity, cmd.schema_name;
     END IF;
  END LOOP;
END;
$function$;

CREATE OR REPLACE FUNCTION public.set_updated_at()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  AS $function$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$function$;

ALTER TABLE "public"."profiles"
  ADD CONSTRAINT "profiles_id_fkey" FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE CASCADE;

ALTER TABLE "public"."role_permissions"
  ADD CONSTRAINT "role_permissions_permission_id_fkey" FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;

ALTER TABLE "public"."role_permissions"
  ADD CONSTRAINT "role_permissions_role_id_fkey" FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;

ALTER TABLE "public"."user_roles"
  ADD CONSTRAINT "user_roles_role_id_fkey" FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;

ALTER TABLE "public"."user_roles"
  ADD CONSTRAINT "user_roles_user_id_fkey" FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;

CREATE INDEX idx_profiles_phone ON public.profiles USING btree (phone_number);

CREATE INDEX idx_role_permissions_permission_id ON public.role_permissions USING btree (permission_id);

CREATE INDEX idx_role_permissions_role_id ON public.role_permissions USING btree (role_id);

CREATE INDEX idx_user_roles_role_id ON public.user_roles USING btree (role_id);

CREATE INDEX idx_user_roles_user_id ON public.user_roles USING btree (user_id);

CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_new_user();

CREATE TRIGGER trg_profiles_updated_at
  BEFORE UPDATE ON public.profiles
  FOR EACH ROW
  EXECUTE FUNCTION public.set_updated_at();

CREATE POLICY "Admins can manage permissions" ON "public"."permissions"
  FOR ALL
  TO PUBLIC
  USING (public.is_admin());

CREATE POLICY "Authenticated users can read permissions" ON "public"."permissions"
  FOR SELECT
  TO "authenticated"
  USING (true);

CREATE POLICY "Admins can manage all profiles" ON "public"."profiles"
  FOR ALL
  TO PUBLIC
  USING (public.is_admin());

CREATE POLICY "Users can update their own profile" ON "public"."profiles"
  FOR UPDATE
  TO PUBLIC
  USING (((auth.uid() = id) OR public.is_admin()))
  WITH CHECK (((auth.uid() = id) OR public.is_admin()));

CREATE POLICY "Users can view their own profile" ON "public"."profiles"
  FOR SELECT
  TO PUBLIC
  USING (((auth.uid() = id) OR public.is_admin()));

CREATE POLICY "Admins can manage role_permissions" ON "public"."role_permissions"
  FOR ALL
  TO PUBLIC
  USING (public.is_admin());

CREATE POLICY "Authenticated users can read role_permissions" ON "public"."role_permissions"
  FOR SELECT
  TO "authenticated"
  USING (true);

CREATE POLICY "Admins can manage roles" ON "public"."roles"
  FOR ALL
  TO PUBLIC
  USING (public.is_admin());

CREATE POLICY "Authenticated users can read roles" ON "public"."roles"
  FOR SELECT
  TO "authenticated"
  USING (true);

CREATE POLICY "Admins can manage user_roles" ON "public"."user_roles"
  FOR ALL
  TO PUBLIC
  USING (public.is_admin());

CREATE POLICY "Users can view their own roles" ON "public"."user_roles"
  FOR SELECT
  TO PUBLIC
  USING (((auth.uid() = user_id) OR public.is_admin()));

CREATE EVENT TRIGGER "ensure_rls"
  ON ddl_command_end
  WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
  EXECUTE FUNCTION "public"."rls_auto_enable"();

REVOKE ALL ON FUNCTION "public"."custom_access_token_hook"(jsonb) FROM "postgres";

GRANT EXECUTE ON FUNCTION "public"."custom_access_token_hook"(jsonb) TO "postgres";

GRANT EXECUTE ON FUNCTION "public"."custom_access_token_hook"(jsonb) TO "service_role", "supabase_auth_admin";

GRANT EXECUTE ON FUNCTION "public"."handle_new_user"() TO PUBLIC, "anon", "authenticated";

REVOKE ALL ON FUNCTION "public"."handle_new_user"() FROM "postgres";

GRANT EXECUTE ON FUNCTION "public"."handle_new_user"() TO "postgres";

GRANT EXECUTE ON FUNCTION "public"."handle_new_user"() TO "service_role";

GRANT EXECUTE ON FUNCTION "public"."is_admin"() TO PUBLIC, "anon", "authenticated";

REVOKE ALL ON FUNCTION "public"."is_admin"() FROM "postgres";

GRANT EXECUTE ON FUNCTION "public"."is_admin"() TO "postgres";

GRANT EXECUTE ON FUNCTION "public"."is_admin"() TO "service_role";

GRANT EXECUTE ON FUNCTION "public"."rls_auto_enable"() TO PUBLIC, "anon", "authenticated";

REVOKE ALL ON FUNCTION "public"."rls_auto_enable"() FROM "postgres";

GRANT EXECUTE ON FUNCTION "public"."rls_auto_enable"() TO "postgres";

GRANT EXECUTE ON FUNCTION "public"."rls_auto_enable"() TO "service_role";

GRANT EXECUTE ON FUNCTION "public"."set_updated_at"() TO PUBLIC, "anon", "authenticated";

REVOKE ALL ON FUNCTION "public"."set_updated_at"() FROM "postgres";

GRANT EXECUTE ON FUNCTION "public"."set_updated_at"() TO "postgres";

GRANT EXECUTE ON FUNCTION "public"."set_updated_at"() TO "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."permissions" TO "anon", "authenticated";

REVOKE ALL ON TABLE "public"."permissions" FROM "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."permissions" TO "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."permissions" TO "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."profiles" TO "anon", "authenticated";

REVOKE ALL ON TABLE "public"."profiles" FROM "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."profiles" TO "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."profiles" TO "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."role_permissions" TO "anon", "authenticated";

REVOKE ALL ON TABLE "public"."role_permissions" FROM "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."role_permissions" TO "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."role_permissions" TO "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."roles" TO "anon", "authenticated";

REVOKE ALL ON TABLE "public"."roles" FROM "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."roles" TO "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."roles" TO "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."user_roles" TO "anon", "authenticated";

REVOKE ALL ON TABLE "public"."user_roles" FROM "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."user_roles" TO "postgres";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."user_roles" TO "service_role";

