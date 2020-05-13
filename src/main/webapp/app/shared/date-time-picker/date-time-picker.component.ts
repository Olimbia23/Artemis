import { Component, ElementRef, EventEmitter, forwardRef, Input, Output, ViewChild } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { isMoment, Moment } from 'moment';
import * as moment from 'moment';

@Component({
    selector: 'jhi-date-time-picker',
    template: `
        <label class="form-control-label" *ngIf="labelName">
            {{ labelName }}
        </label>
        <div class="d-flex">
            <input
                #dateInput="ngModel"
                class="form-control position-relative pl-5"
                [ngClass]="{ 'is-invalid': error }"
                [ngModel]="value"
                [disabled]="disabled"
                [min]="min?.isValid() ? min.toDate() : null"
                [max]="max?.isValid() ? max.toDate() : null"
                (ngModelChange)="updateField($event)"
                [owlDateTime]="dt"
                name="datePicker"
            />
            <button [owlDateTimeTrigger]="dt" class="btn position-absolute" type="button">
                <fa-icon [icon]="'calendar-alt'"></fa-icon>
            </button>
            <owl-date-time [startAt]="startAt?.isValid() ? startAt.toDate() : null" #dt></owl-date-time>
        </div>
    `,
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            multi: true,
            useExisting: forwardRef(() => FormDateTimePickerComponent),
        },
    ],
})
export class FormDateTimePickerComponent implements ControlValueAccessor {
    @ViewChild('dateInput', { static: false }) dateInput: ElementRef;
    @Input() labelName: string;
    @Input() value: any;
    @Input() disabled: boolean;
    @Input() error: boolean;
    @Input() startAt: Moment; // Default selected date.
    @Input() min: Moment; // Dates before this date are not selectable.
    @Input() max: Moment; // Dates after this date are not selectable.
    @Output() valueChange = new EventEmitter();

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    _onChange = (val: Moment) => {};

    /** Wrapper Function to emit the value change from component.
     * @method
     * @fires valueChange
     */
    valueChanged() {
        this.valueChange.emit();
    }

    /** Wrapper function to write {@param value} to {@link value} safely.
     * Converts {@link Moment} to {@link Date}, because owl-date-time only works correctly with {@link Date} objects
     * @method
     * @param value as {@link Moment} or {@link Date}
     */
    writeValue(value: any) {
        // convert moment to date, because owl-date-time only works correctly with date objects
        if (isMoment(value)) {
            this.value = (value as Moment).toDate();
        } else {
            this.value = value;
        }
    }

    /**
     * Registers a callback function {@param fn}. Is called by the forms API on initialization to update the form model on touch.
     * @param fn The callback function to be registered.
     */
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    registerOnTouched(fn: any) {}

    /** Registers a callback function {@param fn}. Is called when the control's value changes in the UI.
     * @param fn The callback function to be registered.
     */
    registerOnChange(fn: any) {
        this._onChange = fn;
    }

    /** Wrapper function to update {@link value}.
     * It triggers {@link _onChange} with {@param newValue} and {@link valueChanged}.
     * @method
     * @param {Moment} newValue  The new value.
     */
    updateField(newValue: Moment) {
        this.value = newValue;
        this._onChange(moment(this.value));
        this.valueChanged();
    }
}
