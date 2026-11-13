-- ==============================================================================
-- HomeCare Platform — Supabase RBAC Seed Extension (Organization Module)
-- Target: Supabase PostgreSQL (idempotent, no migration required)
-- ==============================================================================

INSERT INTO public.permissions (code, name, module, description) VALUES
    ('organization:read', 'Xem thông tin tổ chức', 'ORGANIZATION', 'Xem hồ sơ tổ chức và danh sách thành viên'),
    ('organization:manage', 'Quản lý tổ chức', 'ORGANIZATION', 'Quản lý thông tin tổ chức và phân quyền thành viên nội bộ'),
    ('organization:review', 'Kiểm duyệt tổ chức', 'ORGANIZATION', 'Phê duyệt, từ chối hoặc tạm ngừng hoạt động tổ chức')
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    module = EXCLUDED.module,
    description = EXCLUDED.description;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'ORGANIZATION'
  AND p.code IN ('organization:read', 'organization:manage')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN ('organization:read', 'organization:manage', 'organization:review')
ON CONFLICT (role_id, permission_id) DO NOTHING;
