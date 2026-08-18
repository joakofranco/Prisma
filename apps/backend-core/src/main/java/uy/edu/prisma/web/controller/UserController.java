package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.UserService;
import uy.edu.prisma.web.dto.Dto.CreateUserDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;
import uy.edu.prisma.web.dto.Dto.UserDto;

@RestController
@RequestMapping("/api/users")
public class UserController {

  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  // Ver el comentario en OrganizationController.list: page es 1-indexed en la API, se convierte
  // a 0-indexed recien al construir el Pageable.
  @GetMapping
  public ResponseEntity<PaginatedDto<UserDto>> list(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize) {
    return ResponseEntity.ok(service.list(search, Math.max(0, page - 1), pageSize));
  }

  @GetMapping("/{id}")
  public ResponseEntity<UserDto> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getById(id));
  }

  // ORG_RESPONSIBLE es el "administrador" de su propia organización: puede dar de alta,
  // editar y borrar usuarios, pero UserService acota cada operación a su propio tenant y le
  // impide otorgar el rol global PRISMA_ADMIN (ver los comentarios ahí). PRISMA_ADMIN sigue
  // siendo el único rol sin esa restricción -- administra a través de todos los tenants.
  @PostMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<UserDto> update(
      @PathVariable UUID id, @Valid @RequestBody CreateUserDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
