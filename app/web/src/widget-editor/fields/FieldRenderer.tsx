import type { PropField } from '../../types/widget'
import { StringField } from './StringField'
import { IntegerField } from './IntegerField'
import { BooleanField } from './BooleanField'
import { EnumField } from './EnumField'
import { KoreaLocationField } from './KoreaLocationField'
import { AssetField } from './AssetField'
import { UnsupportedField } from './UnsupportedField'

/** descriptor.fields[].kind에 따라 자동으로 입력기를 고른다(작업지시서 07 7.1-6). */
export function FieldRenderer({
  field,
  value,
  onChange,
  error,
}: {
  field: PropField
  value: unknown
  onChange: (value: unknown) => void
  error?: string
}) {
  switch (field.kind) {
    case 'string':
    case 'text':
      return <StringField field={field} value={value} onChange={onChange} error={error} />
    case 'integer':
      return <IntegerField field={field} value={value} onChange={onChange} error={error} />
    case 'boolean':
      return <BooleanField field={field} value={value} onChange={onChange} />
    case 'enum':
      return <EnumField field={field} value={value} onChange={onChange} error={error} />
    case 'koreaLocation':
      return <KoreaLocationField field={field} value={value} onChange={onChange} error={error} />
    case 'asset':
      return <AssetField field={field} value={value} onChange={onChange} error={error} />
    default:
      return <UnsupportedField field={field} />
  }
}
