package uy.edu.prisma.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uy.edu.prisma.application.ControlGuideService;
import uy.edu.prisma.web.dto.Dto.ControlGuideImportResultDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideItemDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideSummaryDto;
import uy.edu.prisma.web.dto.Dto.SaveControlGuideDto;

@ExtendWith(MockitoExtension.class)
class ControlGuideControllerTest {

  @Mock private ControlGuideService service;

  private MockMvc mvc;
  private final UUID controlId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new ControlGuideController(service)).build();
  }

  private ControlGuideItemDto item() {
    return new ControlGuideItemDto(controlId, "PS.1-1", "Desc", 1, "PS.1", "I", "E", "V");
  }

  @Test
  void summaryReturnsTheCounts() throws Exception {
    when(service.summary("5.0")).thenReturn(new ControlGuideSummaryDto(671, 10));

    mvc.perform(get("/api/catalog/5.0/guide"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(671))
        .andExpect(jsonPath("$.withGuide").value(10));
  }

  @Test
  void listReturnsTheControlsWithTheirGuide() throws Exception {
    when(service.list("5.0")).thenReturn(List.of(item()));

    mvc.perform(get("/api/catalog/5.0/guide/items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("PS.1-1"))
        .andExpect(jsonPath("$[0].interpretation").value("I"));
  }

  @Test
  void exportDownloadsTheCsvAsAnAttachment() throws Exception {
    when(service.exportCsv("5.0")).thenReturn("﻿codigo\r\n");

    mvc.perform(get("/api/catalog/5.0/guide/export"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("guia-controles-5.0.csv")))
        .andExpect(content().contentTypeCompatibleWith("text/csv"));
  }

  @Test
  void importReturnsTheResult() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "guia.csv", "text/csv", "x".getBytes());
    when(service.importGuide(org.mockito.ArgumentMatchers.eq("5.0"), any()))
        .thenReturn(new ControlGuideImportResultDto(3, 2, List.of("X-1"), List.of(), 5));

    mvc.perform(multipart("/api/catalog/5.0/guide").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(2))
        .andExpect(jsonPath("$.notInCatalog[0]").value("X-1"));
  }

  @Test
  void savePassesTheThreeSections() throws Exception {
    when(service.save(any(), any())).thenReturn(item());

    mvc.perform(
            put("/api/catalog/controls/{id}/guide", controlId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"interpretation\":\"I\",\"example\":\"E\",\"evidence\":\"V\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.example").value("E"));
    verify(service).save(controlId, new SaveControlGuideDto("I", "E", "V"));
  }

  @Test
  void deleteTakesTheListOfControlIds() throws Exception {
    when(service.delete(List.of(controlId))).thenReturn(1);

    mvc.perform(
            post("/api/catalog/guide/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlIds\":[\"" + controlId + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.removed").value(1));
  }

  @Test
  void deleteWithoutControlsIsRejected() throws Exception {
    mvc.perform(
            post("/api/catalog/guide/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlIds\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void everyEndpointIsOnlyForPrismaAdmin() {
    PreAuthorize annotation = ControlGuideController.class.getAnnotation(PreAuthorize.class);
    assertNotNull(annotation);
    assertEquals("hasRole('PRISMA_ADMIN')", annotation.value());
  }
}
