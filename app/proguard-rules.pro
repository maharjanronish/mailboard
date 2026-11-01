# Departa uses no reflection and no annotations processing.
# Manifest-referenced components (activities, widget receiver/service) are
# kept automatically by the Android Gradle Plugin. R8 full mode is safe.

# Keep kotlin stdlib behavior if aggressive stripping complains (usually not needed):
# -keep class kotlin.Metadata { *; }
