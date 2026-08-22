"""Carga y troceo de documentos (PDF/DOCX/texto), compartido entre VectorStore (catalogo MCU
5.0) y EvidenceStore (evidencias subidas por las organizaciones).

Para PDF, cada pagina llega como un Document separado con ``metadata['page']`` (0-indexed, via
PyPDFLoader): es la unica fuente real de "pagina" que tenemos, asi que el resto del pipeline la
usa tal cual en vez de inventar paginacion para formatos que no la tienen (DOCX/TXT).

Para la seccion (titulo/encabezado bajo el que cae cada fragmento) no hay una fuente estructurada
equivalente en ninguno de los tres formatos una vez que llegan a texto plano, asi que se detecta
con una heuristica liviana sobre el texto -- ver `_detect_headings`. Para DOCX sí sabemos el
estilo real de cada parrafo (via python-docx), asi que los que son de estilo "Heading"/"Título"
se marcan con prefijo Markdown ("## ") al armar el texto para que esa misma heuristica los
reconozca como el resto de los formatos.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

logger = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = {".pdf", ".txt", ".md", ".markdown", ".docx"}

# Encabezados Markdown ("## Titulo"), numeracion tipo "3.2 Nombre de la seccion" / "3.2.1) Nombre"
# y lineas cortas en MAYUSCULAS (habitual en PDFs institucionales). Ninguno es un parser real de
# estructura -- por eso la seccion detectada es un dato adicional en la cita, nunca el unico
# criterio de ubicacion (la pagina, cuando existe, sigue siendo la referencia principal).
_MD_HEADING_RE = re.compile(r"^#{1,6}[ \t]+(.{2,120}?)[ \t]*$", re.MULTILINE)
_NUMBERED_HEADING_RE = re.compile(
    r"^\d{1,2}(?:\.\d{1,2}){0,3}[\.\)]?[ \t]+([A-ZÁÉÍÓÚÑ][^\n]{2,100}?)[ \t]*$", re.MULTILINE
)
_CAPS_HEADING_RE = re.compile(r"^([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ0-9 ,\-:]{3,79})[ \t]*$", re.MULTILINE)


def load_file(path: Path) -> list[Document]:
    """Carga un archivo soportado. Devuelve una lista vacia si el formato no se soporta o falla
    la lectura (se registra el error, no se propaga: un documento illegible no debe tumbar la
    ingesta del resto)."""
    extension = path.suffix.lower()
    if extension not in SUPPORTED_EXTENSIONS:
        logger.info("Extensión no soportada, se omite: %s", path.name)
        return []
    try:
        if extension == ".pdf":
            return _load_pdf(path)
        if extension in {".txt", ".md", ".markdown"}:
            text = path.read_text(encoding="utf-8", errors="replace")
            return [Document(page_content=text, metadata={"source": str(path)})]
        if extension == ".docx":
            return _load_docx(path)
    except Exception:
        logger.exception("Error leyendo documento %s", path)
    return []


def _load_pdf(path: Path) -> list[Document]:
    from langchain_community.document_loaders import PyPDFLoader

    return PyPDFLoader(str(path)).load()


def _load_docx(path: Path) -> list[Document]:
    from docx import Document as DocxDocument

    lines: list[str] = []
    for paragraph in DocxDocument(str(path)).paragraphs:
        text = paragraph.text.strip()
        if not text:
            continue
        style_name = (paragraph.style.name if paragraph.style else "") or ""
        # "Heading 1".."Heading 9" en Word en ingles, "Título 1".."Título 9" en Word en español.
        if "heading" in style_name.lower() or "títul" in style_name.lower():
            lines.append(f"## {text}")
        else:
            lines.append(text)
    return [Document(page_content="\n".join(lines), metadata={"source": str(path)})]


def build_splitter(chunk_size: int, chunk_overlap: int) -> RecursiveCharacterTextSplitter:
    return RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        separators=["\n\n", "\n", " ", ""],
    )


def _detect_headings(text: str) -> list[tuple[int, str]]:
    """Devuelve (offset, titulo) de cada encabezado detectado en `text`, ordenados por offset."""
    headings: list[tuple[int, str]] = []
    for pattern in (_MD_HEADING_RE, _NUMBERED_HEADING_RE, _CAPS_HEADING_RE):
        for match in pattern.finditer(text):
            heading = match.group(1).strip().strip("#").strip()
            if heading:
                headings.append((match.start(), heading))
    headings.sort(key=lambda h: h[0])
    return headings


def _section_at(headings: list[tuple[int, str]], offset: int) -> str | None:
    """Ultimo encabezado detectado en `headings` antes (o en) `offset`, o None si no hay ninguno."""
    section: str | None = None
    for pos, heading in headings:
        if pos > offset:
            break
        section = heading
    return section


def split_with_sections(doc: Document, splitter: RecursiveCharacterTextSplitter) -> list[Document]:
    """Trocea `doc` igual que ``splitter.split_documents([doc])``, pero ademas calcula bajo que
    encabezado (heuristica, ver `_detect_headings`) cae cada fragmento resultante y lo deja en
    ``metadata['section']`` (None si no se detecto ninguno antes del fragmento).

    Se busca el offset de cada fragmento dentro del texto original con un cursor que solo avanza
    hacia adelante -- los fragmentos salen en orden de la fuente, así que no hace falta un
    `find()` global (mas lento y ambiguo si el mismo texto se repite) para ubicarlos.
    """
    text = doc.page_content
    headings = _detect_headings(text)
    chunks: list[Document] = []
    cursor = 0
    for chunk_text in splitter.split_text(text):
        offset = text.find(chunk_text, cursor)
        if offset == -1:
            offset = text.find(chunk_text)
        if offset != -1:
            cursor = offset
        section = _section_at(headings, offset if offset != -1 else cursor)
        chunks.append(
            Document(page_content=chunk_text, metadata={**doc.metadata, "section": section})
        )
    return chunks
