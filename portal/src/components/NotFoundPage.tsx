/**
 * The portal's opaque 404 page (issue #37, S8-03, PRD 12): unknown
 * and unauthorized are the SAME page — a deep link to a version or
 * application the tester cannot reach renders exactly what a typo'd
 * URL renders, with no hint the resource exists.
 */
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import { Box, Button, Card, CardContent, Stack, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router-dom";

export default function NotFoundPage() {
  return (
    <Card data-testid="not-found-page">
      <CardContent>
        <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 4 }}>
          <Typography variant="h5" component="h1">
            Page not found
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 360 }}>
            This page does not exist, or you are not authorized to view it.
          </Typography>
          <Box>
            <Button
              size="small"
              variant="outlined"
              component={RouterLink}
              to="/"
              startIcon={<ArrowBackIcon />}
              data-testid="not-found-back"
            >
              My Applications
            </Button>
          </Box>
        </Stack>
      </CardContent>
    </Card>
  );
}
