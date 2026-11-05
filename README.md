# Medical Roster Rebuild — Android starter

Fresh Android/Kotlin project implementing a first Colab-inspired image pipeline.

## Included
- JPEG/PNG Android document picker
- original decoded Bitmap retained for crops and colour sampling
- OpenCV grayscale, CLAHE, adaptive thresholding
- horizontal/vertical morphological opening
- projection profiles and line-run grouping
- regular-spacing day-column selection
- ML Kit full-image OCR and exact employee-ID matching
- per-day crop creation from original pixels
- provisional RGB-to-Lab colour classification
- ML Kit cell OCR
- evidence statuses: DIRECTLY_OBSERVED, OCR_ONLY, COLOR_ONLY, CONFLICT, UNKNOWN, MISSING_CROP
- no database or calendar writes

## Open
1. Unzip the project.
2. Open the `MedicalRosterRebuild` directory in Android Studio.
3. Use JDK 17 and let Gradle sync.
4. Run on a physical Android device.
5. Select a roster image, enter ID/month/year, then run extraction.

## Important: experimental, not validated
- PDF import is not implemented.
- Grid detection is a first heuristic and must be checked on the supplied September and June images.
- The row-boundary logic currently uses all detected horizontal-line runs; structural header/body/footer segmentation still needs to be implemented.
- The daily-gap prior is estimated from image width and month length. It is not a universal template model.
- Colour references are approximate RGB medians from the September sample, converted to Lab. The original labelled 30-cell calibration dataset is not embedded.
- EXIF orientation handling, annotated overlays, crop montage, and JSON export remain to be added.
- Do not use the extracted schedule operationally until geometry, crops, colour confusion matrix, OCR and physical-device tests pass.

## Next steps
1. Add saved intermediate images and an annotated grid overlay.
2. Add a montage with every expected day and explicit missing-crop labels.
3. Validate all 31 September day boundaries and held-out June.
4. Structurally identify the employee table body and row intervals.
5. Build a proper labelled calibration dataset from verified original crops.
6. Add JSON diagnostics and stage timing.
7. Add PDF rendering after image geometry is stable.
8. Add calendar import only after validation gates pass.

## Build
```bash
./gradlew test
./gradlew assembleDebug
```
