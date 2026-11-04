package org.orgkeep.api.model;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Rejects the names a catalog cannot take ({@link Catalogs#isReserved}). The format itself (slug) is checked by the
 * other constraints of the field: a null value is valid here.
 * <p>
 * A custom Bean Validation constraint: the annotation names its validator, which Hibernate Validator instantiates
 * itself (no CDI bean). A violation gives the same 400 as {@code @Pattern}.
 */
@Documented
@Constraint(validatedBy = CatalogName.Validator.class)
@Target({FIELD, PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CatalogName {
    String message() default "reserved name: a claim of the access token, or the prefix 'org-' of the organisation clients";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<CatalogName, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || !Catalogs.isReserved(value);
        }
    }
}
