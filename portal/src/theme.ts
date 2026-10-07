import { createTheme } from "@mui/material/styles";

/** Portal theme (issue #35): the tester-facing sibling of the console
 * theme — same brand palette, sized for phones first (comfortable tap
 * targets, 16px base so iOS does not zoom focused inputs). */
export const portalTheme = createTheme({
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
    htmlFontSize: 16,
  },
  shape: { borderRadius: 12 },
  components: {
    MuiButton: {
      styleOverrides: {
        root: { minHeight: 44 },
      },
    },
    MuiTextField: {
      defaultProps: { fullWidth: true },
    },
  },
});
