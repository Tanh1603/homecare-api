-- ==============================================================================
-- Migration: 20261111093000_profile_status_and_rbac_master_data.sql
-- Description:
--   1. Add status column to public.profiles with ACTIVE, SUSPENDED, BANNED check
--   2. Update custom_access_token_hook to inject user_status into JWT claims
--   3. Seed master data: 4 canonical roles, granular domain permissions, and role_permissions
-- ==============================================================================

-- 1. Schema Alterations: Add status column to public.profiles
ALTER TABLE "public"."profiles"
  ADD COLUMN IF NOT EXISTS "status" character varying(20) NOT NULL DEFAULT 'ACTIVE'::character varying
  CONSTRAINT "profiles_status_check" CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED'));

CREATE INDEX IF NOT EXISTS "idx_profiles_status" ON "public"."profiles" USING btree ("status");

-- 2. Update custom_access_token_hook to inject user_status into JWT claims
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
    v_user_status TEXT;
    v_claims JSONB;
BEGIN
    v_user_id := (event->>'user_id')::UUID;
    v_claims := event->'claims';

    -- 1. Query user status from public.profiles
    SELECT status
    INTO v_user_status
    FROM public.profiles
    WHERE id = v_user_id;

    -- 2. Query distinct role codes
    SELECT array_agg(DISTINCT r.code)
    INTO v_user_roles
    FROM public.user_roles ur
    JOIN public.roles r ON ur.role_id = r.id
    WHERE ur.user_id = v_user_id;

    -- 3. Query distinct permission codes via role_permissions
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

    -- 4. Inject claims into access token JWT
    v_claims := jsonb_set(v_claims, '{user_status}', to_jsonb(COALESCE(v_user_status, 'ACTIVE')));
    v_claims := jsonb_set(v_claims, '{user_roles}', to_jsonb(v_user_roles));
    v_claims := jsonb_set(v_claims, '{permissions}', to_jsonb(v_permissions));

    -- Return updated event
    event := jsonb_set(event, '{claims}', v_claims);
    RETURN event;
END;
$function$;

GRANT EXECUTE ON FUNCTION "public"."custom_access_token_hook"(jsonb) TO "supabase_auth_admin", "service_role";
REVOKE ALL ON FUNCTION "public"."custom_access_token_hook"(jsonb) FROM PUBLIC, "anon", "authenticated";

-- 3. Master Data: 4 Canonical Roles
INSERT INTO public.roles (id, code, name, description) VALUES
    ('00000000-0000-0000-0001-000000000001', 'ADMIN', 'Quản trị viên', 'Toàn quyền kiểm soát và quản trị toàn bộ hệ thống HomeCare'),
    ('00000000-0000-0000-0001-000000000002', 'ORGANIZATION', 'Đối tác / Cửa hàng', 'Tổ chức cung cấp dịch vụ sửa chữa và bán linh kiện, vật tư'),
    ('00000000-0000-0000-0001-000000000003', 'TECHNICIAN', 'Kỹ thuật viên', 'Kỹ thuật viên thực hiện các dịch vụ sửa chữa, bảo trì thiết bị'),
    ('00000000-0000-0000-0001-000000000004', 'CUSTOMER', 'Khách hàng', 'Người dùng cá nhân sở hữu thiết bị, đặt lịch dịch vụ và mua sắm')
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description;

-- 4. Master Data: Granular Permissions
INSERT INTO public.permissions (code, name, module, description) VALUES
    -- HOUSEHOLD Module
    ('household:read', 'Xem thông tin hộ gia đình', 'HOUSEHOLD', 'Xem danh sách và chi tiết hộ gia đình'),
    ('household:write', 'Quản lý hộ gia đình', 'HOUSEHOLD', 'Thêm, sửa, xóa hộ gia đình'),
    ('device:read', 'Xem danh sách thiết bị', 'HOUSEHOLD', 'Xem thông tin thiết bị điện máy gia dụng'),
    ('device:write', 'Quản lý thiết bị', 'HOUSEHOLD', 'Thêm, sửa đổi, cập nhật ETag, xóa thiết bị'),
    ('warranty:read', 'Xem thông tin bảo hành', 'HOUSEHOLD', 'Xem thẻ bảo hành thiết bị và thời hạn'),
    ('warranty:write', 'Quản lý bảo hành', 'HOUSEHOLD', 'Kích hoạt, liên kết hoặc gia hạn bảo hành'),
    ('maintenance:read', 'Xem lịch bảo trì', 'HOUSEHOLD', 'Xem kế hoạch và nhắc nhở bảo trì thiết bị'),
    ('maintenance:write', 'Quản lý lịch bảo trì', 'HOUSEHOLD', 'Đặt lịch, xác nhận hoàn thành bảo trì'),

    -- PROVIDER Module
    ('service:read', 'Xem danh mục dịch vụ', 'PROVIDER', 'Xem danh mục dịch vụ sửa chữa, bảo dưỡng'),
    ('service:manage', 'Quản lý dịch vụ sửa chữa', 'PROVIDER', 'Tạo, sửa đổi gói dịch vụ, giá niêm yết'),
    ('technician:read', 'Xem thông tin KTV', 'PROVIDER', 'Xem hồ sơ kỹ thuật viên, đánh giá'),
    ('technician:manage', 'Quản lý kỹ thuật viên', 'PROVIDER', 'Thêm mới, phê duyệt, phân bổ kỹ thuật viên'),
    ('schedule:manage', 'Quản lý khung giờ làm việc', 'PROVIDER', 'Tạo và cập nhật ca trực, khung giờ rảnh'),

    -- BOOKING Module
    ('booking:create', 'Đặt lịch dịch vụ', 'BOOKING', 'Tạo yêu cầu đặt lịch sửa chữa, bảo hành'),
    ('booking:read', 'Xem đơn đặt lịch', 'BOOKING', 'Xem danh sách và tiến độ lịch hẹn'),
    ('booking:cancel', 'Hủy lịch dịch vụ', 'BOOKING', 'Hủy lịch hẹn theo quy định hủy đơn'),
    ('booking:dispatch', 'Điều phối kỹ thuật viên', 'BOOKING', 'Điều phối hoặc can thiệp phân bổ kỹ thuật viên'),
    ('booking:update_status', 'Cập nhật tiến độ sửa chữa', 'BOOKING', 'KTV check-in, cập nhật ghi chú, hoàn thành dịch vụ'),

    -- COMMERCE Module
    ('product:read', 'Xem sản phẩm & linh kiện', 'COMMERCE', 'Xem danh sách linh kiện, vật tư điện gia dụng'),
    ('product:manage', 'Quản lý sản phẩm', 'COMMERCE', 'Thêm mới, sửa giá, quản lý tồn kho sản phẩm'),
    ('order:create', 'Đặt mua linh kiện', 'COMMERCE', 'Tạo đơn đặt mua phụ tùng, linh kiện'),
    ('order:read', 'Xem đơn hàng mua sắm', 'COMMERCE', 'Xem danh sách và chi tiết đơn mua hàng'),
    ('order:fulfill', 'Đóng gói & Giao hàng', 'COMMERCE', 'Cập nhật trạng thái fulfillment đóng gói và giao hàng'),
    ('order:return', 'Yêu cầu đổi trả', 'COMMERCE', 'Tạo yêu cầu đổi trả linh kiện/vật tư lỗi'),
    ('order:return_resolve', 'Xử lý yêu cầu đổi trả', 'COMMERCE', 'Duyệt hoặc từ chối yêu cầu đổi trả hàng'),

    -- REVIEW Module
    ('review:create', 'Viết đánh giá', 'REVIEW', 'Đánh giá dịch vụ hoàn thành hoặc linh kiện đã nhận'),
    ('review:read', 'Xem đánh giá', 'REVIEW', 'Xem danh sách đánh giá của sản phẩm và dịch vụ'),
    ('review:moderate', 'Kiểm duyệt đánh giá', 'REVIEW', 'Ẩn hoặc xử lý các đánh giá vi phạm tiêu chuẩn'),

    -- NOTIFICATION Module
    ('notification:read', 'Xem thông báo', 'NOTIFICATION', 'Xem hộp thư thông báo cá nhân'),
    ('notification:write', 'Gửi thông báo hệ thống', 'NOTIFICATION', 'Gửi thông báo broadcast hoặc cảnh báo toàn hệ thống'),

    -- ADMIN Module
    ('admin:all', 'Toàn quyền quản trị', 'ADMIN', 'Quyền lực tối cao, truy cập mọi chức năng quản trị'),
    ('user:manage', 'Quản lý người dùng', 'ADMIN', 'Phân vai trò, khóa hoặc mở khóa tài khoản người dùng')
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    module = EXCLUDED.module,
    description = EXCLUDED.description;

-- 5. Master Data: Role - Permission Mappings
-- A. CUSTOMER Permissions
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'CUSTOMER'
  AND p.code IN (
    'household:read', 'household:write',
    'device:read', 'device:write',
    'warranty:read',
    'maintenance:read', 'maintenance:write',
    'service:read',
    'booking:create', 'booking:read', 'booking:cancel',
    'product:read',
    'order:create', 'order:read', 'order:return',
    'review:create', 'review:read',
    'notification:read'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- B. TECHNICIAN Permissions
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'TECHNICIAN'
  AND p.code IN (
    'service:read', 'technician:read', 'schedule:manage',
    'booking:read', 'booking:update_status',
    'device:read', 'warranty:read', 'maintenance:read',
    'review:read', 'notification:read'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- C. ORGANIZATION Permissions
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'ORGANIZATION'
  AND p.code IN (
    'service:read', 'service:manage',
    'technician:read', 'technician:manage', 'schedule:manage',
    'booking:read', 'booking:dispatch',
    'product:read', 'product:manage',
    'order:read', 'order:fulfill', 'order:return_resolve',
    'warranty:read', 'warranty:write',
    'review:read', 'notification:read'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- D. ADMIN Permissions (All permissions)
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
