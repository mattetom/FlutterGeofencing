# Publishing `geofencing_service` to pub.dev

This guide outlines how to release a new version of the package, following the [official publishing documentation](https://dart.dev/tools/pub/publishing).

## Prerequisites

1. A **[pub.dev](https://pub.dev/)** account associated with the publishing user or organization.
2. A **connected GitHub (or OAuth) account** for your **verified publisher**, if you use one.
3. A **publish token** (or an integrated login flow) — see below.

### Login / token (one-off or CI)

- From a terminal, the recommended flow is:
  - `dart pub token add https://pub.dev`
  - Complete the steps to open a browser and create a token, or use a [permanent token from pub.dev](https://pub.dev/tokens) for headless/CI.
- Alternatively, with a Google-linked account, use `dart pub login` if supported in your environment.

## Checklist for each release

1. **`pubspec.yaml`**
   - Bump `version` (e.g. `1.1.1`, using [semantic versioning](https://semver.org/)).
   - Update `description` and other metadata as needed.
2. **`CHANGELOG.md`**
   - Add a new `## <version>` section at the top for changes, fixes, and breaking changes.
3. **`README.md`**
   - Update any version (`^x.y.z`) or new API notes.
4. **Example and platforms**
   - Confirm the `example/` app builds (`flutter build apk` / `flutter build ios` where applicable).
5. **Tests**
   - Run `flutter test` in the plugin root, if a `test/` directory exists.
6. **iOS (optional but recommended)**
   - Keep `s.version` in `ios/geofencing_service.podspec` aligned with the package version for CocoaPods users.
7. **Git**
   - Commit the changes, and if you use tags, run `git tag v1.1.1` and `git push origin v1.1.1` after merging to the main branch.

## Pre-publish validation (required)

From the **plugin root** (the folder that contains the plugin’s `pubspec.yaml`, not `example`):

```bash
dart pub publish --dry-run
```

- The command should finish **without errors** (branch cleanliness may cause warnings).
- Review any warnings: pub.dev scores the package on documentation, static analysis, license plausibility, and more.
- The archive must be under 100 MB and must not include secrets, `.env` files, or similar.

## Publishing

```bash
dart pub publish
```

- Confirm when prompted. If you use a token, ensure it is still valid.
- After upload, the package page (e.g. `https://pub.dev/packages/geofencing_service`) will show the new version shortly.

## After publishing

- Check that the released README and changelog on pub.dev look correct.
- Update issues or **GitHub Releases** if you use that workflow.
- If something went wrong, fix the code, bump the version, and publish again — you generally **cannot** republish the same version on pub.dev.

## Further reading

- [Publishing packages](https://dart.dev/tools/pub/publishing)
- [The pubspec](https://dart.dev/tools/pub/pubspec#repository)
- [Package score and quality requirements](https://pub.dev/packages/geofencing_service/score)
