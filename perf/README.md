# perf

Pruebas de performance de PRISMA con JMeter. La guía completa está en [docs/desarrollo/PERFORMANCE.md](../docs/desarrollo/PERFORMANCE.md).

- `jmeter/`: planes `.jmx` (se pueden abrir con la GUI de JMeter).
- `casos/`: un `.properties` por caso de prueba; es lo que se ajusta.
- `scripts/`: `perf.sh` (orquesta todo), `setup_data.py` y `cleanup_data.py` (datos `PERF-`), `summary.py` (reporte y veredicto).
- `results/` y `data/`: salida y credenciales generadas; no se versionan.

```
make perf-setup
make perf CASO=CP-RND-01
```
