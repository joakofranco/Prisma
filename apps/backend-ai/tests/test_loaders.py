"""Tests de la deteccion de encabezados y el troceo con seccion (`app.rag.loaders`).

`split_with_sections` es lo que le da a las citas de evidencia (ver `test_evidence_rag.py`) el
dato de "seccion" ademas de "pagina" y "fragmento" -- estos tests cubren la heuristica en
aislamiento, sin pasar por la ingesta completa.
"""

from __future__ import annotations

from langchain_core.documents import Document

from app.rag import loaders


def _splitter(chunk_size: int = 60, chunk_overlap: int = 0):
    return loaders.build_splitter(chunk_size, chunk_overlap)


def test_detect_headings_markdown() -> None:
    text = "## Gestión de Riesgos\nTexto del párrafo bajo el encabezado."
    headings = loaders._detect_headings(text)
    assert headings == [(0, "Gestión de Riesgos")]


def test_detect_headings_numbered_section() -> None:
    text = "3.2 Control de Accesos\nDescripción del control numerado."
    headings = loaders._detect_headings(text)
    assert headings and headings[0][1] == "Control de Accesos"


def test_detect_headings_all_caps_line() -> None:
    text = "GESTION DE INCIDENTES\nEl parrafo describe el proceso de gestion de incidentes."
    headings = loaders._detect_headings(text)
    assert headings and headings[0][1] == "GESTION DE INCIDENTES"


def test_detect_headings_ignores_plain_paragraphs() -> None:
    text = "esto es un párrafo normal en minúsculas, sin ningún encabezado."
    assert loaders._detect_headings(text) == []


def test_section_at_returns_nearest_preceding_heading() -> None:
    headings = [(0, "Introducción"), (50, "Alcance"), (120, "Definiciones")]
    assert loaders._section_at(headings, 10) == "Introducción"
    assert loaders._section_at(headings, 60) == "Alcance"
    assert loaders._section_at(headings, 200) == "Definiciones"


def test_section_at_returns_none_before_first_heading() -> None:
    headings = [(30, "Alcance")]
    assert loaders._section_at(headings, 5) is None


def test_split_with_sections_tags_chunks_after_their_heading() -> None:
    text = (
        "## Introducción\n"
        + ("intro " * 15)
        + "\n## Control de Accesos\n"
        + ("control de accesos " * 15)
    )
    doc = Document(page_content=text, metadata={"page": 0})
    chunks = loaders.split_with_sections(doc, _splitter())

    assert len(chunks) > 1
    sections = {c.metadata["section"] for c in chunks}
    assert "Introducción" in sections
    assert "Control de Accesos" in sections
    # La pagina original se preserva en cada fragmento (metadata se combina, no se reemplaza).
    assert all(c.metadata["page"] == 0 for c in chunks)


def test_split_with_sections_without_headings_leaves_section_none() -> None:
    doc = Document(page_content="texto plano sin encabezados " * 10, metadata={})
    chunks = loaders.split_with_sections(doc, _splitter())
    assert all(c.metadata["section"] is None for c in chunks)


def test_load_docx_marks_heading_styles_as_markdown(tmp_path) -> None:
    docx = __import__("docx")
    document = docx.Document()
    document.add_paragraph("Política de Seguridad", style="Heading 1")
    document.add_paragraph("Todo el personal debe cumplir la política vigente.")
    path = tmp_path / "politica.docx"
    document.save(str(path))

    loaded = loaders.load_file(path)
    assert len(loaded) == 1
    assert loaded[0].page_content.startswith("## Política de Seguridad")

    headings = loaders._detect_headings(loaded[0].page_content)
    assert headings and headings[0][1] == "Política de Seguridad"
