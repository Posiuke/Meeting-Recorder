export default function Spinner({ label }: { label?: string }) {
  return (
    <div className="spinner-wrap" role="status">
      <div className="spinner" />
      {label && <span className="spinner-label">{label}</span>}
    </div>
  );
}
