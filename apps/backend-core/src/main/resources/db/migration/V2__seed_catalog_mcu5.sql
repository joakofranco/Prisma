-- =====================================================================
-- V2__seed_catalog_mcu5.sql - Datos semilla del catalogo MCU 5.0
-- =====================================================================

-- Version del catalogo
INSERT INTO prisma.catalog_versions (id, version, label, active) VALUES
('a0000000-0000-0000-0000-000000000001', '5.0', 'Marco de Ciberseguridad MCU 5.0 - AGESIC', true);

-- FUNCION 1: Gestion de Riesgos
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'F1', 'Gestion de Riesgos', 'Identificacion, analisis y tratamiento de riesgos de ciberseguridad', 1);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000001', 'f0000001-0000-0000-0000-000000000001', 'F1.C1', 'Identificacion de Activos', 'Inventario y clasificacion de activos de informacion', 1),
('c0000001-0002-0000-0000-000000000001', 'f0000001-0000-0000-0000-000000000001', 'F1.C2', 'Evaluacion de Riesgos', 'Analisis de probabilidad e impacto', 2);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000001', 'c0000001-0001-0000-0000-000000000001', 'F1.C1.SC1', 'Inventario de Activos', 'Registro de todos los activos de informacion', 1),
('a1000001-0002-0000-0000-000000000001', 'c0000001-0001-0000-0000-000000000001', 'F1.C1.SC2', 'Clasificacion de Activos', 'Categorizacion por sensibilidad e importancia', 2),
('a1000001-0003-0000-0000-000000000001', 'c0000001-0002-0000-0000-000000000001', 'F1.C2.SC1', 'Analisis de Riesgos', 'Identificacion y valoracion de amenazas', 1),
('a1000001-0004-0000-0000-000000000001', 'c0000001-0002-0000-0000-000000000001', 'F1.C2.SC2', 'Tratamiento de Riesgos', 'Seleccion y aplicacion de controles', 2);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000001', 'a1000001-0001-0000-0000-000000000001', 'R-F1.C1.SC1.1', 'Mantener inventario actualizado de activos', 1),
('b1000001-0002-0000-0000-000000000001', 'a1000001-0002-0000-0000-000000000001', 'R-F1.C1.SC2.1', 'Definir criterios de clasificacion', 1),
('b1000001-0003-0000-0000-000000000001', 'a1000001-0003-0000-0000-000000000001', 'R-F1.C2.SC1.1', 'Realizar analisis de riesgos periodico', 1),
('b1000001-0004-0000-0000-000000000001', 'a1000001-0004-0000-0000-000000000001', 'R-F1.C2.SC2.1', 'Definir plan de tratamiento', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000001', 'b1000001-0001-0000-0000-000000000001', 'CT-F1.C1.SC1.1.1', 'Listado de hardware, software y datos', 3, 1),
('d1000001-0002-0000-0000-000000000001', 'b1000001-0002-0000-0000-000000000001', 'CT-F1.C1.SC2.1.1', 'Matriz de clasificacion por niveles', 3, 1),
('d1000001-0003-0000-0000-000000000001', 'b1000001-0003-0000-0000-000000000001', 'CT-F1.C2.SC1.1.1', 'Metodologia formal de analisis', 4, 1),
('d1000001-0004-0000-0000-000000000001', 'b1000001-0004-0000-0000-000000000001', 'CT-F1.C2.SC2.1.1', 'Documentacion de acciones de tratamiento', 3, 1);

-- FUNCION 2: Seguridad de la Informacion
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'F2', 'Seguridad de la Informacion', 'Proteccion de la confidencialidad, integridad y disponibilidad', 2);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000002', 'f0000001-0000-0000-0000-000000000002', 'F2.C1', 'Clasificacion de Datos', 'Categorizacion de informacion por sensibilidad', 1),
('c0000001-0002-0000-0000-000000000002', 'f0000001-0000-0000-0000-000000000002', 'F2.C2', 'Cifrado', 'Proteccion de datos en transito y en reposo', 2);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000002', 'c0000001-0001-0000-0000-000000000002', 'F2.C1.SC1', 'Politica de Clasificacion', 'Directrices de etiquetado y manejo', 1),
('a1000001-0002-0000-0000-000000000002', 'c0000001-0002-0000-0000-000000000002', 'F2.C2.SC1', 'Cifrado en Transito', 'Proteccion de datos en movimiento', 1),
('a1000001-0003-0000-0000-000000000002', 'c0000001-0002-0000-0000-000000000002', 'F2.C2.SC2', 'Cifrado en Reposo', 'Proteccion de datos almacenados', 2);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000002', 'a1000001-0001-0000-0000-000000000002', 'R-F2.C1.SC1.1', 'Implementar politica de clasificacion', 1),
('b1000001-0002-0000-0000-000000000002', 'a1000001-0002-0000-0000-000000000002', 'R-F2.C2.SC1.1', 'Cifrar comunicaciones sensibles', 1),
('b1000001-0003-0000-0000-000000000002', 'a1000001-0003-0000-0000-000000000002', 'R-F2.C2.SC2.1', 'Cifrar bases de datos y archivos', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000002', 'b1000001-0001-0000-0000-000000000002', 'CT-F2.C1.SC1.1.1', 'Manual de politica de clasificacion publicado', 3, 1),
('d1000001-0002-0000-0000-000000000002', 'b1000001-0002-0000-0000-000000000002', 'CT-F2.C2.SC1.1.1', 'TLS 1.2+ en todos los servicios', 4, 1),
('d1000001-0003-0000-0000-000000000002', 'b1000001-0003-0000-0000-000000000002', 'CT-F2.C2.SC2.1.1', 'AES-256 para datos en reposo', 4, 1);

-- FUNCION 3: Seguridad de las Operaciones
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'F3', 'Seguridad de las Operaciones', 'Proteccion de las operaciones tecnologicas', 3);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000003', 'f0000001-0000-0000-0000-000000000003', 'F3.C1', 'Gestion de Cambios', 'Control de modificaciones a la infraestructura', 1),
('c0000001-0002-0000-0000-000000000003', 'f0000001-0000-0000-0000-000000000003', 'F3.C2', 'Respaldos', 'Copias de seguridad y recuperacion', 2);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000003', 'c0000001-0001-0000-0000-000000000003', 'F3.C1.SC1', 'Proceso de Cambio', 'Procedimiento formal de gestion de cambios', 1),
('a1000001-0002-0000-0000-000000000003', 'c0000001-0002-0000-0000-000000000003', 'F3.C2.SC1', 'Politica de Respaldos', 'Frecuencia y retencion de respaldos', 1);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000003', 'a1000001-0001-0000-0000-000000000003', 'R-F3.C1.SC1.1', 'Definir proceso de control de cambios', 1),
('b1000001-0002-0000-0000-000000000003', 'a1000001-0002-0000-0000-000000000003', 'R-F3.C2.SC1.1', 'Establecer politica de respaldos', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000003', 'b1000001-0001-0000-0000-000000000003', 'CT-F3.C1.SC1.1.1', 'Comite de cambios documentado', 3, 1),
('d1000001-0002-0000-0000-000000000003', 'b1000001-0002-0000-0000-000000000003', 'CT-F3.C2.SC1.1.1', 'Respaldos diarios automatizados', 4, 1);

-- FUNCION 4: Seguridad del Personal
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'F4', 'Seguridad del Personal', 'Seguridad en el recurso humano', 4);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000004', 'f0000001-0000-0000-0000-000000000004', 'F4.C1', 'Concientizacion', 'Programas de capacitacion en seguridad', 1);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000004', 'c0000001-0001-0000-0000-000000000004', 'F4.C1.SC1', 'Programa de Formacion', 'Actividades de educacion en seguridad', 1);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000004', 'a1000001-0001-0000-0000-000000000004', 'R-F4.C1.SC1.1', 'Implementar programa anual de concientizacion', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000004', 'b1000001-0001-0000-0000-000000000004', 'CT-F4.C1.SC1.1.1', 'Capacitacion anual obligatoria para todo el personal', 3, 1);

-- FUNCION 5: Gestion de Activos
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'F5', 'Gestion de Activos', 'Administracion segura de activos de informacion y tecnologia', 5);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000005', 'f0000001-0000-0000-0000-000000000005', 'F5.C1', 'Ciclo de Vida', 'Gestion del ciclo de vida de los activos', 1);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000005', 'c0000001-0001-0000-0000-000000000005', 'F5.C1.SC1', 'Gestion del Ciclo de Vida', 'Control de adquisicion, mantenimiento y disposicion', 1);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000005', 'a1000001-0001-0000-0000-000000000005', 'R-F5.C1.SC1.1', 'Definir procedimiento de gestion del ciclo de vida', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000005', 'b1000001-0001-0000-0000-000000000005', 'CT-F5.C1.SC1.1.1', 'Procedimiento documentado de gestion de activos', 3, 1);

-- FUNCION 6: Control de Acceso
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'F6', 'Control de Acceso', 'Gestion de accesos a sistemas y datos', 6);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000006', 'f0000001-0000-0000-0000-000000000006', 'F6.C1', 'Gestion de Accesos', 'Provision y revocacion de accesos', 1),
('c0000001-0002-0000-0000-000000000006', 'f0000001-0000-0000-0000-000000000006', 'F6.C2', 'Autenticacion', 'Mecanismos de autenticacion', 2);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000006', 'c0000001-0001-0000-0000-000000000006', 'F6.C1.SC1', 'Politica de Acceso', 'Principio de menor privilegio', 1),
('a1000001-0002-0000-0000-000000000006', 'c0000001-0002-0000-0000-000000000006', 'F6.C2.SC1', 'Gestion de Contrasenas', 'Politica de contrasenas robustas', 1);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000006', 'a1000001-0001-0000-0000-000000000006', 'R-F6.C1.SC1.1', 'Definir politica de control de accesos', 1),
('b1000001-0002-0000-0000-000000000006', 'a1000001-0002-0000-0000-000000000006', 'R-F6.C2.SC1.1', 'Implementar politica de contrasenas', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000006', 'b1000001-0001-0000-0000-000000000006', 'CT-F6.C1.SC1.1.1', 'Revision trimestral de accesos', 4, 1),
('d1000001-0002-0000-0000-000000000006', 'b1000001-0002-0000-0000-000000000006', 'CT-F6.C2.SC1.1.1', 'Contrasenas de 12+ caracteres con complejidad', 3, 1);

-- FUNCION 7: Seguridad del Desarrollo
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'F7', 'Seguridad del Desarrollo', 'Seguridad en el ciclo de vida del desarrollo', 7);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000007', 'f0000001-0000-0000-0000-000000000007', 'F7.C1', 'Seguridad en Desarrollo', 'Practicas seguras de codificacion', 1);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000007', 'c0000001-0001-0000-0000-000000000007', 'F7.C1.SC1', 'Revision de Codigo', 'Analisis estatico y revisiones de seguridad', 1);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000007', 'a1000001-0001-0000-0000-000000000007', 'R-F7.C1.SC1.1', 'Establecer revisiones de seguridad del codigo', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000007', 'b1000001-0001-0000-0000-000000000007', 'CT-F7.C1.SC1.1.1', 'Analisis estatico en pipeline CI/CD', 3, 1);

-- FUNCION 8: Seguridad de la Continuidad
INSERT INTO prisma.catalog_functions (id, version_id, code, name, description, sort_order) VALUES
('f0000001-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'F8', 'Seguridad de la Continuidad', 'Planificacion de continuidad del negocio', 8);

INSERT INTO prisma.catalog_categories (id, function_id, code, name, description, sort_order) VALUES
('c0000001-0001-0000-0000-000000000008', 'f0000001-0000-0000-0000-000000000008', 'F8.C1', 'Continuidad del Negocio', 'Planes de recuperacion y continuidad', 1);

INSERT INTO prisma.catalog_subcategories (id, category_id, code, name, description, sort_order) VALUES
('a1000001-0001-0000-0000-000000000008', 'c0000001-0001-0000-0000-000000000008', 'F8.C1.SC1', 'Plan de Continuidad', 'Estrategia de continuidad del negocio', 1);

INSERT INTO prisma.catalog_requirements (id, subcategory_id, code, description, sort_order) VALUES
('b1000001-0001-0000-0000-000000000008', 'a1000001-0001-0000-0000-000000000008', 'R-F8.C1.SC1.1', 'Definir plan de continuidad del negocio', 1);

INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order) VALUES
('d1000001-0001-0000-0000-000000000008', 'b1000001-0001-0000-0000-000000000008', 'CT-F8.C1.SC1.1.1', 'Plan documentado y probado anualmente', 3, 1);
