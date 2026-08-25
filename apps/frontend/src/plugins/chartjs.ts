// Registro centralizado de los componentes de Chart.js que usan los gráficos de
// src/components/charts/. Se importa una sola vez (ver main.ts) por efecto secundario;
// vue-chartjs no registra nada por su cuenta, así que sin esto los <Doughnut>/<Bar>/<Radar>
// fallan en tiempo de ejecución.
import {
  Chart as ChartJS,
  ArcElement,
  BarElement,
  CategoryScale,
  LinearScale,
  RadialLinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
} from 'chart.js';

ChartJS.register(
  ArcElement,
  BarElement,
  CategoryScale,
  LinearScale,
  RadialLinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
);
