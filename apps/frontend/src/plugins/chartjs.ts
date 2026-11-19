// Registro centralizado de los componentes de Chart.js que usan los gráficos de
// src/components/charts/. Se importa una sola vez (ver main.ts) por efecto secundario;
// vue-chartjs no registra nada por su cuenta, así que sin esto los <Doughnut>/<Bar>/<Radar>
// fallan en tiempo de ejecución.
import {
  Chart as ChartJS,
  ArcElement,
  BarElement,
  BarController,
  LineController,
  CategoryScale,
  LinearScale,
  RadialLinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
} from 'chart.js';

// BarController y LineController: el gráfico de avance (PRI-321) mezcla barras y líneas en un mismo
// lienzo, y un dataset de otro tipo que el del componente necesita su controlador registrado.
ChartJS.register(
  ArcElement,
  BarElement,
  BarController,
  LineController,
  CategoryScale,
  LinearScale,
  RadialLinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
);
