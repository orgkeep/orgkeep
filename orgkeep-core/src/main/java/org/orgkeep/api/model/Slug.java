package org.orgkeep.api.model;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * The format of every name of Orgkeep (organisation, catalog, product, feature, role, group, service account...):
 * a lowercase slug, kebab-case. Checked when a name is created (fields of the requests) and when a name is read
 * from a path (the @PathParam of the resources): a name that cannot exist is refused with a 400 before any
 * lookup, and never reaches a lock, Keycloak or a log line (no line break can be injected in the logs).
 * <p>
 * A composed constraint: the annotation carries a {@code @Pattern}, so there is no validator class to write.
 * {@code @ReportAsSingleViolation} gives its own message instead of the one of the pattern. A null value is valid,
 * like for {@code @Pattern}: {@code @NotBlank} is the job of the field.
 */
@Documented
@Pattern(regexp = Slug.REGEXP)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Slug {
    String REGEXP = "[a-z0-9]([a-z0-9-]*[a-z0-9])?";

    String message() default "lowercase letters, digits and inner hyphens only";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
