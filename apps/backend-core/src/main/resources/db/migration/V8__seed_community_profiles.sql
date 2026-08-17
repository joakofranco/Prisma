-- Perfiles comunitarios de ejemplo sobre el catálogo semilla MCU 5.0 (V2). Los nombres y la
-- estructura (Básico ⊆ Estándar ⊆ Avanzado, niveles crecientes de exigencia) siguen a los
-- "Perfil comunitario BASICO/ESTANDAR/AVANZADO" reales que define el marco MCU 5.0
-- (docs/mcu-5.0/*.xlsx), pero el subconjunto de controles concreto de cada uno es orientativo:
-- el catálogo sembrado en V2 es un subconjunto reducido de 15 controles con fines de desarrollo,
-- no el catálogo completo del marco (que usa la taxonomía GV/ID/PR/DE/RS/RC de esos Excel) --
-- un PRISMA_ADMIN puede editarlos o crear los suyos desde la pantalla de administración de
-- perfiles una vez cargado el catálogo real.

INSERT INTO prisma.community_profiles (id, name, description, catalog_version) VALUES
('e1000001-0000-0000-0000-000000000001', 'Básico',
 'Línea base mínima: inventario de activos, clasificación de la información, control de acceso, contraseñas seguras, respaldos y capacitación del personal.',
 '5.0'),
('e1000001-0000-0000-0000-000000000002', 'Estándar',
 'Amplía el nivel Básico con cifrado en tránsito y en reposo, análisis formal de riesgos, gestión de cambios y continuidad del servicio.',
 '5.0'),
('e1000001-0000-0000-0000-000000000003', 'Avanzado',
 'Cobertura completa del catálogo semilla: suma a Estándar la gestión de activos, la trazabilidad de riesgos y la seguridad del ciclo de desarrollo (CI/CD).',
 '5.0');

-- Básico: inventario, clasificación, respaldos, control de acceso, contraseñas, capacitación.
INSERT INTO prisma.community_profile_controls (profile_id, control_id) VALUES
('e1000001-0000-0000-0000-000000000001', 'd1000001-0001-0000-0000-000000000001'), -- inventario de activos
('e1000001-0000-0000-0000-000000000001', 'd1000001-0001-0000-0000-000000000002'), -- clasificacion de la informacion
('e1000001-0000-0000-0000-000000000001', 'd1000001-0002-0000-0000-000000000003'), -- respaldos diarios
('e1000001-0000-0000-0000-000000000001', 'd1000001-0001-0000-0000-000000000006'), -- revision de accesos
('e1000001-0000-0000-0000-000000000001', 'd1000001-0002-0000-0000-000000000006'), -- contrasenas seguras
('e1000001-0000-0000-0000-000000000001', 'd1000001-0001-0000-0000-000000000004'); -- capacitacion anual

-- Estándar: todo lo de Básico + cifrado en transito/reposo, analisis de riesgos, gestion de
-- cambios y continuidad.
INSERT INTO prisma.community_profile_controls (profile_id, control_id) VALUES
('e1000001-0000-0000-0000-000000000002', 'd1000001-0001-0000-0000-000000000001'),
('e1000001-0000-0000-0000-000000000002', 'd1000001-0001-0000-0000-000000000002'),
('e1000001-0000-0000-0000-000000000002', 'd1000001-0002-0000-0000-000000000003'),
('e1000001-0000-0000-0000-000000000002', 'd1000001-0001-0000-0000-000000000006'),
('e1000001-0000-0000-0000-000000000002', 'd1000001-0002-0000-0000-000000000006'),
('e1000001-0000-0000-0000-000000000002', 'd1000001-0001-0000-0000-000000000004'),
('e1000001-0000-0000-0000-000000000002', 'd1000001-0002-0000-0000-000000000002'), -- TLS en transito
('e1000001-0000-0000-0000-000000000002', 'd1000001-0003-0000-0000-000000000002'), -- AES-256 en reposo
('e1000001-0000-0000-0000-000000000002', 'd1000001-0003-0000-0000-000000000001'), -- metodologia de analisis de riesgos
('e1000001-0000-0000-0000-000000000002', 'd1000001-0001-0000-0000-000000000003'), -- comite de cambios
('e1000001-0000-0000-0000-000000000002', 'd1000001-0001-0000-0000-000000000008'); -- plan de continuidad

-- Avanzado: todo lo de Estándar + gestion de activos, matriz de clasificacion, trazabilidad del
-- tratamiento de riesgos y seguridad del ciclo de desarrollo. Es el catálogo semilla completo.
INSERT INTO prisma.community_profile_controls (profile_id, control_id) VALUES
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000001'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000002'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0002-0000-0000-000000000003'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000006'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0002-0000-0000-000000000006'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000004'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0002-0000-0000-000000000002'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0003-0000-0000-000000000002'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0003-0000-0000-000000000001'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000003'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000008'),
('e1000001-0000-0000-0000-000000000003', 'd1000001-0002-0000-0000-000000000001'), -- matriz de clasificacion
('e1000001-0000-0000-0000-000000000003', 'd1000001-0004-0000-0000-000000000001'), -- tratamiento de riesgos
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000005'), -- gestion de activos
('e1000001-0000-0000-0000-000000000003', 'd1000001-0001-0000-0000-000000000007'); -- CI/CD analisis estatico
