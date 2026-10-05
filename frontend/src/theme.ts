import { createTheme } from "@mui/material/styles";

/** Console theme (issue #12): light, dense administration surface. */
export const consoleTheme = createTheme({
  cssVariables: true,
  palette: {
    mode: "light",
    primary: { main: "#2f5aa8" },
    secondary: { main: "#0e7c66" },
  },
  typography: {
    fontFamily: [
      "Inter",
      "system-ui",
      "-apple-system",
      "Segoe UI",
      "Roboto",
      "Helvetica Neue",
      "sans-serif",
    ].join(","),
  },
});
